package com.saltnote.ui.platform

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas as AndroidCanvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.DashPathEffect
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.PorterDuff
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.ExifInterface
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.os.Build
import android.os.SystemClock
import android.view.Choreographer
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CameraAlt
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.LockOpen
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.input.motionprediction.MotionEventPredictor
import androidx.lifecycle.LifecycleOwner
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.saltnote.bridge.StrokeSample
import com.saltnote.ui.components.SaltFilterChip
import com.saltnote.ui.components.StrokeGeometry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.hypot
import kotlin.math.roundToInt

object AndroidPlatformContext {
    lateinit var appContext: Context
    var lifecycleOwner: LifecycleOwner? = null
    var imagePickerBridge: (() -> ByteArray?)? = null
    var cameraPermissionBridge: (((Boolean) -> Unit) -> Unit)? = null
    var micPermissionBridge: (((Boolean) -> Unit) -> Unit)? = null
    var folderTreePickerBridge: (((android.net.Uri?) -> Unit) -> Unit)? = null
    private var multicastLock: WifiManager.MulticastLock? = null

    fun isInitialized(): Boolean = ::appContext.isInitialized

    fun setMulticastLock(enabled: Boolean) {
        if (!::appContext.isInitialized) return
        runCatching {
            if (enabled) {
                if (multicastLock == null) {
                    val wm = appContext.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
                    multicastLock = wm?.createMulticastLock("saltnote-lan-sync")?.apply {
                        setReferenceCounted(false)
                    }
                }
                if (multicastLock?.isHeld == false) {
                    multicastLock?.acquire()
                }
            } else {
                if (multicastLock?.isHeld == true) {
                    multicastLock?.release()
                }
            }
        }
    }
}

private const val SALT_ANDROID_PREFS = "saltnote_android_prefs"
private const val KEY_LAST_VAULT_DIR = "last_vault_dir"

actual fun systemLanguageTag(): String {
    if (!AndroidPlatformContext.isInitialized()) return java.util.Locale.getDefault().toLanguageTag()
    val locale = AndroidPlatformContext.appContext.resources.configuration.locales[0]
        ?: java.util.Locale.getDefault()
    return locale.toLanguageTag()
}

actual fun saveLastVaultDirectory(path: String) {
    if (!AndroidPlatformContext.isInitialized()) return
    AndroidPlatformContext.appContext
        .getSharedPreferences(SALT_ANDROID_PREFS, Context.MODE_PRIVATE)
        .edit()
        .putString(KEY_LAST_VAULT_DIR, path.trim())
        .apply()
}

private fun isPosixDirWritable(dir: File): Boolean {
    if (!dir.exists() && !dir.mkdirs()) return false
    if (!dir.isDirectory) return false
    return runCatching {
        val probe = File(dir, ".saltnote_probe_${System.nanoTime()}")
        probe.writeText("ok")
        probe.delete()
        true
    }.getOrDefault(false)
}

actual fun defaultVaultDirectory(): String {
    if (AndroidPlatformContext.isInitialized()) {
        val saved = AndroidPlatformContext.appContext
            .getSharedPreferences(SALT_ANDROID_PREFS, Context.MODE_PRIVATE)
            .getString(KEY_LAST_VAULT_DIR, null)
            ?.trim()
        if (!saved.isNullOrBlank()) {
            val savedDir = File(saved)
            if (isPosixDirWritable(savedDir)) {
                return savedDir.absolutePath
            }
        }
    }
    val base = AndroidPlatformContext.appContext.getExternalFilesDir(null)
        ?: AndroidPlatformContext.appContext.filesDir
    return File(base, "SaltNoteVault").apply { mkdirs() }.absolutePath
}

actual fun defaultDeviceName(): String = "${Build.MANUFACTURER} ${Build.MODEL}"

actual fun loadImageBitmapFromVault(vaultPath: String, relPath: String): ImageBitmap? {
    val file = File(vaultPath, relPath)
    if (!file.exists()) return null
    return runCatching {
        BitmapFactory.decodeFile(file.absolutePath)?.asImageBitmap()
    }.getOrNull()
}

actual fun readVaultAssetBytes(vaultPath: String, relPath: String): ByteArray? {
    val file = File(vaultPath, relPath)
    if (!file.isFile) return null
    return runCatching { file.readBytes() }.getOrNull()
}

actual fun pickLocalImageBytes(): ByteArray? = AndroidPlatformContext.imagePickerBridge?.invoke()

private fun resolvePosixDirectoryFromTreeUri(treeUri: android.net.Uri): File? {
    val docId = runCatching { android.provider.DocumentsContract.getTreeDocumentId(treeUri) }.getOrNull()
        ?: return null
    val parts = docId.split(":", limit = 2)
    val volume = parts.getOrNull(0).orEmpty()
    val rel = parts.getOrNull(1).orEmpty().trim('/')
    return when {
        volume == "raw" && rel.isNotBlank() -> File("/$rel").let { f -> if (f.exists()) f else File(docId.removePrefix("raw:")) }
        volume == "primary" -> {
            val root = android.os.Environment.getExternalStorageDirectory()
            if (rel.isEmpty()) root else File(root, rel)
        }
        volume == "home" -> {
            val docs = File(android.os.Environment.getExternalStorageDirectory(), "Documents")
            if (rel.isEmpty()) docs else File(docs, rel)
        }
        volume.isNotBlank() -> {
            val storageCandidate = File("/storage/$volume", rel)
            if (storageCandidate.exists()) storageCandidate else null
        }
        else -> null
    }
}

private fun safFallbackVaultDir(treeUri: android.net.Uri): File {
    val appCtx = AndroidPlatformContext.appContext
    val docId = runCatching { android.provider.DocumentsContract.getTreeDocumentId(treeUri) }.getOrDefault("Vault")
    val rawName = docId.substringAfterLast('/').substringAfterLast(':').ifBlank { "Vault" }
    val safeName = rawName.replace(Regex("[^a-zA-Z0-9._\\-\\u4e00-\\u9fff]"), "_").ifBlank { "Vault" }
    val vaultsRoot = appCtx.getExternalFilesDir("Vaults") ?: File(appCtx.filesDir, "Vaults")
    return File(vaultsRoot, safeName).apply { mkdirs() }
}

private fun findChildDocumentUri(
    resolver: android.content.ContentResolver,
    treeUri: android.net.Uri,
    parentDocUri: android.net.Uri,
    displayName: String,
): Pair<android.net.Uri, String>? {
    val parentDocId = android.provider.DocumentsContract.getDocumentId(parentDocUri)
    val childrenUri = android.provider.DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentDocId)
    val projection = arrayOf(
        android.provider.DocumentsContract.Document.COLUMN_DOCUMENT_ID,
        android.provider.DocumentsContract.Document.COLUMN_DISPLAY_NAME,
        android.provider.DocumentsContract.Document.COLUMN_MIME_TYPE,
    )
    resolver.query(childrenUri, projection, null, null, null)?.use { cursor ->
        val idCol = cursor.getColumnIndexOrThrow(android.provider.DocumentsContract.Document.COLUMN_DOCUMENT_ID)
        val nameCol = cursor.getColumnIndexOrThrow(android.provider.DocumentsContract.Document.COLUMN_DISPLAY_NAME)
        val mimeCol = cursor.getColumnIndexOrThrow(android.provider.DocumentsContract.Document.COLUMN_MIME_TYPE)
        while (cursor.moveToNext()) {
            if (cursor.getString(nameCol) == displayName) {
                val docId = cursor.getString(idCol)
                val mime = cursor.getString(mimeCol).orEmpty()
                return android.provider.DocumentsContract.buildDocumentUriUsingTree(treeUri, docId) to mime
            }
        }
    }
    return null
}

private fun ensureSafSubdir(
    resolver: android.content.ContentResolver,
    treeUri: android.net.Uri,
    parentDocUri: android.net.Uri,
    name: String,
): android.net.Uri {
    val existing = findChildDocumentUri(resolver, treeUri, parentDocUri, name)
    if (existing != null && existing.second == android.provider.DocumentsContract.Document.MIME_TYPE_DIR) {
        return existing.first
    }
    return android.provider.DocumentsContract.createDocument(
        resolver,
        parentDocUri,
        android.provider.DocumentsContract.Document.MIME_TYPE_DIR,
        name,
    ) ?: error("Failed to create folder: $name")
}

private fun mimeTypeForExportFile(fileName: String): String {
    val lower = fileName.lowercase()
    return when {
        lower.endsWith(".md") -> "text/markdown"
        lower.endsWith(".png") -> "image/png"
        lower.endsWith(".jpg") || lower.endsWith(".jpeg") -> "image/jpeg"
        lower.endsWith(".webp") -> "image/webp"
        else -> "application/octet-stream"
    }
}

private fun copyDirectoryToSafTree(
    resolver: android.content.ContentResolver,
    treeUri: android.net.Uri,
    targetDirUri: android.net.Uri,
    srcDir: File,
) {
    val children = srcDir.listFiles() ?: return
    for (child in children) {
        if (child.isDirectory) {
            val subUri = ensureSafSubdir(resolver, treeUri, targetDirUri, child.name)
            copyDirectoryToSafTree(resolver, treeUri, subUri, child)
        } else if (child.isFile) {
            val existing = findChildDocumentUri(resolver, treeUri, targetDirUri, child.name)
            val docUri = if (existing != null && existing.second != android.provider.DocumentsContract.Document.MIME_TYPE_DIR) {
                existing.first
            } else {
                android.provider.DocumentsContract.createDocument(
                    resolver,
                    targetDirUri,
                    mimeTypeForExportFile(child.name),
                    child.name,
                ) ?: error("Failed to create file: ${child.name}")
            }
            resolver.openOutputStream(docUri, "wt")?.use { out ->
                child.inputStream().use { inp -> inp.copyTo(out) }
            } ?: resolver.openOutputStream(docUri, "w")?.use { out ->
                child.inputStream().use { inp -> inp.copyTo(out) }
            } ?: error("Failed to open output stream: ${child.name}")
        }
    }
}

actual fun pickVaultDirectory(
    initialPath: String,
    strings: com.saltnote.ui.i18n.SaltStrings,
    onPicked: (String) -> Unit,
    onMessage: (String) -> Unit,
) {
    val bridge = AndroidPlatformContext.folderTreePickerBridge
    if (bridge == null) {
        onMessage(strings.vaultSwitchFailedPrefix + "Picker unavailable")
        return
    }
    bridge.invoke { treeUri ->
        if (treeUri == null) return@invoke
        runCatching {
            val flags = android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or
                android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            AndroidPlatformContext.appContext.contentResolver.takePersistableUriPermission(treeUri, flags)
        }
        val candidate = resolvePosixDirectoryFromTreeUri(treeUri)
        val chosenDir = if (candidate != null && isPosixDirWritable(candidate)) {
            candidate
        } else {
            safFallbackVaultDir(treeUri)
        }
        saveLastVaultDirectory(chosenDir.absolutePath)
        onPicked(chosenDir.absolutePath)
    }
}

actual fun exportMarkdownBackupWithPicker(
    client: com.saltnote.bridge.SaltNoteClient,
    initialPath: String,
    strings: com.saltnote.ui.i18n.SaltStrings,
    onResult: (String) -> Unit,
) {
    val bridge = AndroidPlatformContext.folderTreePickerBridge
    if (bridge == null) {
        onResult("${strings.backupFailedPrefix}Picker unavailable")
        return
    }
    bridge.invoke { treeUri ->
        if (treeUri == null) return@invoke
        val appCtx = AndroidPlatformContext.appContext
        runCatching {
            val flags = android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or
                android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            appCtx.contentResolver.takePersistableUriPermission(treeUri, flags)
        }
        val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())
        Thread {
            val stagingDir = File(appCtx.cacheDir, "saltnote_md_export_${System.currentTimeMillis()}").apply { mkdirs() }
            try {
                val count = client.exportMarkdownBackup(stagingDir.absolutePath)
                val treeDocId = android.provider.DocumentsContract.getTreeDocumentId(treeUri)
                val rootDocUri = android.provider.DocumentsContract.buildDocumentUriUsingTree(treeUri, treeDocId)
                copyDirectoryToSafTree(appCtx.contentResolver, treeUri, rootDocUri, stagingDir)
                mainHandler.post { onResult("${strings.backupExportedToast} ($count)") }
            } catch (e: Throwable) {
                mainHandler.post { onResult("${strings.backupFailedPrefix}${e.message ?: ""}") }
            } finally {
                stagingDir.deleteRecursively()
            }
        }.start()
    }
}

actual fun formatUtcTimestamp(utcMs: Long): String {
    if (utcMs <= 0L) return "-"
    val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
    return sdf.format(Date(utcMs))
}

actual fun setLanMulticastLock(enabled: Boolean) {
    AndroidPlatformContext.setMulticastLock(enabled)
}

actual fun copyTextToClipboard(text: String) {
    if (!AndroidPlatformContext.isInitialized()) return
    runCatching {
        val cm = AndroidPlatformContext.appContext.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        cm?.setPrimaryClip(ClipData.newPlainText("SaltNote Pairing", text))
    }
}

actual fun readTextFromClipboard(): String? {
    if (!AndroidPlatformContext.isInitialized()) return null
    return runCatching {
        val cm = AndroidPlatformContext.appContext.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        cm?.primaryClip?.getItemAt(0)?.coerceToText(AndroidPlatformContext.appContext)?.toString()
    }.getOrNull()
}

actual fun supportsLiveCameraQrScan(): Boolean = true

actual fun supportsInboxCameraCapture(): Boolean = true

actual fun hasMicrophonePermission(): Boolean {
    if (!AndroidPlatformContext.isInitialized()) return false
    return ContextCompat.checkSelfPermission(
        AndroidPlatformContext.appContext,
        Manifest.permission.RECORD_AUDIO,
    ) == PackageManager.PERMISSION_GRANTED
}

actual fun requestMicrophonePermission(onResult: (Boolean) -> Unit) {
    val bridge = AndroidPlatformContext.micPermissionBridge
    if (bridge != null) {
        bridge.invoke(onResult)
    } else {
        onResult(hasMicrophonePermission())
    }
}

actual fun isNetworkMetered(): Boolean {
    if (!AndroidPlatformContext.isInitialized()) return false
    val cm = AndroidPlatformContext.appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        ?: return false
    val active = cm.activeNetwork ?: return false
    val caps = cm.getNetworkCapabilities(active) ?: return cm.isActiveNetworkMetered
    val unmetered = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) ||
        caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_TEMPORARILY_NOT_METERED)
    return !unmetered || cm.isActiveNetworkMetered
}

actual fun observeNetworkMetered(onChange: (Boolean) -> Unit): () -> Unit {
    if (!AndroidPlatformContext.isInitialized()) {
        onChange(false)
        return {}
    }
    val cm = AndroidPlatformContext.appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
    if (cm == null) {
        onChange(false)
        return {}
    }
    onChange(isNetworkMetered())
    val cb = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) = onChange(isNetworkMetered())
        override fun onLost(network: Network) = onChange(isNetworkMetered())
        override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
            val unmetered = networkCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) ||
                networkCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_TEMPORARILY_NOT_METERED)
            onChange(!unmetered || cm.isActiveNetworkMetered)
        }
    }
    return runCatching<() -> Unit> {
        cm.registerDefaultNetworkCallback(cb)
        val dispose: () -> Unit = { runCatching { cm.unregisterNetworkCallback(cb) } }
        dispose
    }.getOrElse {
        {}
    }
}

actual fun startVoiceRecording(onError: (String) -> Unit): PlatformVoiceRecorderSession? {
    if (!AndroidPlatformContext.isInitialized()) return null
    val ctx = AndroidPlatformContext.appContext
    if (!hasMicrophonePermission()) {
        onError("Microphone permission not granted")
        return null
    }
    return runCatching {
        val tempFile = File(ctx.cacheDir, "saltnote_opus_${System.currentTimeMillis()}.ogg")
        val am = ctx.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        val focusReq = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
            )
            .build()
        am?.requestAudioFocus(focusReq)

        val recorder = if (Build.VERSION.SDK_INT >= 31) {
            MediaRecorder(ctx)
        } else {
            @Suppress("DEPRECATION")
            MediaRecorder()
        }
        recorder.apply {
            setAudioSource(MediaRecorder.AudioSource.MIC)
            setOutputFormat(MediaRecorder.OutputFormat.OGG)
            setAudioEncoder(MediaRecorder.AudioEncoder.OPUS)
            setAudioSamplingRate(16_000)
            setAudioChannels(1)
            setAudioEncodingBitRate(24_000)
            setOutputFile(tempFile.absolutePath)
            prepare()
            start()
        }
        val startedAtMs = System.currentTimeMillis()
        var released = false

        object : PlatformVoiceRecorderSession {
            override fun stopAndRead(): RecordedAudioClip? {
                if (released) return null
                released = true
                runCatching { recorder.stop() }
                runCatching { recorder.release() }
                runCatching { am?.abandonAudioFocusRequest(focusReq) }
                if (!tempFile.exists() || tempFile.length() == 0L) {
                    runCatching { tempFile.delete() }
                    return null
                }
                val bytes = runCatching { tempFile.readBytes() }.getOrNull()
                runCatching { tempFile.delete() }
                if (bytes == null || bytes.isEmpty()) return null
                val dur = (System.currentTimeMillis() - startedAtMs).coerceAtLeast(300L).toInt()
                return RecordedAudioClip(bytes = bytes, ext = "ogg", durationMs = dur)
            }

            override fun cancel() {
                if (released) return
                released = true
                runCatching { recorder.stop() }
                runCatching { recorder.release() }
                runCatching { am?.abandonAudioFocusRequest(focusReq) }
                runCatching { tempFile.delete() }
            }
        }
    }.getOrElse { err ->
        onError(err.message ?: "Opus recording failed")
        null
    }
}

actual fun playVaultAudio(
    vaultPath: String,
    relPath: String,
    onCompleted: () -> Unit,
    onError: (String) -> Unit,
): PlatformAudioPlaybackHandle? {
    val file = File(vaultPath, relPath)
    if (!file.exists()) {
        onError("Audio file not found: $relPath")
        return null
    }
    return runCatching {
        val player = MediaPlayer().apply {
            setDataSource(file.absolutePath)
            setOnCompletionListener {
                runCatching { release() }
                onCompleted()
            }
            prepare()
            start()
        }
        object : PlatformAudioPlaybackHandle {
            override fun isPlaying(): Boolean = runCatching { player.isPlaying }.getOrDefault(false)
            override fun pause() {
                runCatching { if (player.isPlaying) player.pause() }
            }
            override fun resume() {
                runCatching { if (!player.isPlaying) player.start() }
            }
            override fun stop() {
                runCatching {
                    if (player.isPlaying) player.stop()
                    player.release()
                }
            }
        }
    }.getOrElse { err ->
        onError(err.message ?: "Audio playback failed")
        null
    }
}

fun encodeImageBytesToWebpPair(
    rawBytes: ByteArray,
    isBoardMode: Boolean = false,
): Pair<ByteArray, ByteArray>? = runCatching {
    val targetLimit = if (isBoardMode) 1600 else 1280
    val optsBounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(rawBytes, 0, rawBytes.size, optsBounds)
    val rawW = optsBounds.outWidth.coerceAtLeast(1)
    val rawH = optsBounds.outHeight.coerceAtLeast(1)
    var sampleSize = 1
    while ((rawW / sampleSize) > targetLimit * 2 || (rawH / sampleSize) > targetLimit * 2) {
        sampleSize *= 2
    }
    val decodeOpts = BitmapFactory.Options().apply { inSampleSize = sampleSize }
    val decoded = BitmapFactory.decodeByteArray(rawBytes, 0, rawBytes.size, decodeOpts) ?: return null
    val exif = runCatching { ExifInterface(java.io.ByteArrayInputStream(rawBytes)) }.getOrNull()
    compressOrientedBitmapToWebpPair(decoded, exif, targetLimit, isBoardMode)
}.getOrNull()

private fun compressCapturedPhotoToWebp(
    photoFile: File,
    isBoardMode: Boolean,
): Pair<ByteArray, ByteArray>? = runCatching {
    val optsBounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(photoFile.absolutePath, optsBounds)
    val rawW = optsBounds.outWidth.coerceAtLeast(1)
    val rawH = optsBounds.outHeight.coerceAtLeast(1)
    var sampleSize = 1
    val targetLimit = if (isBoardMode) 1600 else 1280
    while ((rawW / sampleSize) > targetLimit * 2 || (rawH / sampleSize) > targetLimit * 2) {
        sampleSize *= 2
    }
    val decodeOpts = BitmapFactory.Options().apply { inSampleSize = sampleSize }
    val decoded = BitmapFactory.decodeFile(photoFile.absolutePath, decodeOpts) ?: return null
    val exif = runCatching { ExifInterface(photoFile.absolutePath) }.getOrNull()
    compressOrientedBitmapToWebpPair(decoded, exif, targetLimit, isBoardMode)
}.getOrNull()

private fun compressOrientedBitmapToWebpPair(
    decoded: Bitmap,
    exif: ExifInterface?,
    targetLimit: Int,
    isBoardMode: Boolean,
): Pair<ByteArray, ByteArray> {
    val orientation = exif?.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
        ?: ExifInterface.ORIENTATION_NORMAL
    val matrix = Matrix()
    when (orientation) {
        ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
        ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
        ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
        ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.postScale(-1f, 1f)
        ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.postScale(1f, -1f)
    }
    val oriented = if (!matrix.isIdentity) {
        Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, matrix, true).also {
            if (it != decoded) decoded.recycle()
        }
    } else {
        decoded
    }

    val curLong = maxOf(oriented.width, oriented.height).coerceAtLeast(1)
    val scale = if (curLong > targetLimit) targetLimit.toFloat() / curLong.toFloat() else 1f
    val dstW = (oriented.width * scale).roundToInt().coerceAtLeast(1)
    val dstH = (oriented.height * scale).roundToInt().coerceAtLeast(1)
    val scaledPrimary = if (dstW != oriented.width || dstH != oriented.height) {
        Bitmap.createScaledBitmap(oriented, dstW, dstH, true).also {
            if (it != oriented) oriented.recycle()
        }
    } else {
        oriented
    }

    val primaryBitmap = if (isBoardMode) {
        // Mild contrast + brightness boost for whiteboards/blackboards/documents
        val enhanced = Bitmap.createBitmap(scaledPrimary.width, scaledPrimary.height, Bitmap.Config.ARGB_8888)
        val canvas = AndroidCanvas(enhanced)
        val c = 1.18f
        val b = -14f
        val cm = ColorMatrix(
            floatArrayOf(
                c, 0f, 0f, 0f, b,
                0f, c, 0f, 0f, b,
                0f, 0f, c, 0f, b,
                0f, 0f, 0f, 1f, 0f,
            ),
        )
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            colorFilter = ColorMatrixColorFilter(cm)
        }
        canvas.drawBitmap(scaledPrimary, 0f, 0f, paint)
        scaledPrimary.recycle()
        enhanced
    } else {
        scaledPrimary
    }

    val webpFmt = if (Build.VERSION.SDK_INT >= 30) {
        Bitmap.CompressFormat.WEBP_LOSSY
    } else {
        @Suppress("DEPRECATION")
        Bitmap.CompressFormat.WEBP
    }
    val primaryOut = ByteArrayOutputStream()
    primaryBitmap.compress(webpFmt, 82, primaryOut)

    val thumbW = 320.coerceAtMost(primaryBitmap.width).coerceAtLeast(1)
    val thumbH = ((primaryBitmap.height.toFloat() / primaryBitmap.width.coerceAtLeast(1)) * thumbW)
        .roundToInt()
        .coerceAtLeast(1)
    val thumbBitmap = Bitmap.createScaledBitmap(primaryBitmap, thumbW, thumbH, true)
    val thumbOut = ByteArrayOutputStream()
    thumbBitmap.compress(webpFmt, 75, thumbOut)
    if (thumbBitmap != primaryBitmap) thumbBitmap.recycle()
    primaryBitmap.recycle()

    return primaryOut.toByteArray() to thumbOut.toByteArray()
}

@Composable
actual fun PlatformInboxCameraCaptureDialog(
    initialPrivate: Boolean,
    onCaptured: (primaryWebp: ByteArray, thumbWebp: ByteArray, isPrivate: Boolean, noteText: String?) -> Unit,
    onDismiss: () -> Unit,
) {
    val ctx = AndroidPlatformContext.appContext
    val strings = com.saltnote.ui.i18n.LocalSaltStrings.current
    val scope = rememberCoroutineScope()
    var hasPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED,
        )
    }
    var isBoardMode by remember { mutableStateOf(true) }
    var isPrivate by remember { mutableStateOf(initialPrivate) }
    var isProcessing by remember { mutableStateOf(false) }
    var capturedPair by remember { mutableStateOf<Pair<ByteArray, ByteArray>?>(null) }
    var noteText by remember { mutableStateOf("") }
    var cameraError by remember { mutableStateOf<String?>(null) }
    var imageCaptureRef by remember { mutableStateOf<ImageCapture?>(null) }
    val captureExecutor = remember { Executors.newSingleThreadExecutor() }

    DisposableEffect(Unit) {
        onDispose { captureExecutor.shutdown() }
    }

    LaunchedEffect(Unit) {
        if (!hasPermission) {
            AndroidPlatformContext.cameraPermissionBridge?.invoke { granted ->
                hasPermission = granted
                if (!granted) cameraError = strings.cameraPermissionMissing
            }
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(strings.capturePhoto, style = MaterialTheme.typography.titleMedium)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = if (isPrivate) Icons.Rounded.Lock else Icons.Rounded.LockOpen,
                        contentDescription = null,
                        tint = if (isPrivate) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(16.dp),
                    )
                    Spacer(Modifier.width(4.dp))
                    Switch(checked = isPrivate, onCheckedChange = { isPrivate = it })
                }
            }
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                if (capturedPair == null) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        SaltFilterChip(
                            selected = isBoardMode,
                            onClick = { isBoardMode = true },
                            label = { Text(strings.cameraModeBoard) },
                        )
                        SaltFilterChip(
                            selected = !isBoardMode,
                            onClick = { isBoardMode = false },
                            label = { Text(strings.cameraModeJournal) },
                        )
                    }

                    if (hasPermission && AndroidPlatformContext.lifecycleOwner != null) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(300.dp)
                                .clip(RoundedCornerShape(16.dp)),
                        ) {
                            CameraXPhotoCapturePreview(
                                lifecycleOwner = AndroidPlatformContext.lifecycleOwner!!,
                                onImageCaptureReady = { imageCaptureRef = it },
                                onCameraError = { cameraError = it },
                                modifier = Modifier.fillMaxSize(),
                            )
                            if (isProcessing) {
                                Surface(
                                    color = Color.Black.copy(alpha = 0.45f),
                                    modifier = Modifier.fillMaxSize(),
                                ) {
                                    Box(contentAlignment = Alignment.Center) {
                                        CircularProgressIndicator(color = Color.White)
                                    }
                                }
                            }
                        }
                    } else {
                        Text(
                            text = cameraError ?: strings.cameraPermissionPrompt,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        if (!hasPermission) {
                            FilledTonalButton(
                                onClick = {
                                    AndroidPlatformContext.cameraPermissionBridge?.invoke { granted ->
                                        hasPermission = granted
                                    }
                                },
                            ) { Text(strings.grantCameraPermission) }
                        }
                    }
                } else {
                    Text(
                        text = "WebP ✓ (${capturedPair!!.first.size / 1024} KB)",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    OutlinedTextField(
                        value = noteText,
                        onValueChange = { noteText = it },
                        label = { Text(strings.captureText) },
                        maxLines = 3,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }

                cameraError?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                }
            }
        },
        confirmButton = {
            if (capturedPair == null) {
                Button(
                    enabled = hasPermission && imageCaptureRef != null && !isProcessing,
                    onClick = {
                        val cap = imageCaptureRef ?: return@Button
                        isProcessing = true
                        cameraError = null
                        val tmpFile = File(ctx.cacheDir, "saltnote_cap_${System.currentTimeMillis()}.jpg")
                        val outputOpts = ImageCapture.OutputFileOptions.Builder(tmpFile).build()
                        cap.takePicture(
                            outputOpts,
                            captureExecutor,
                            object : ImageCapture.OnImageSavedCallback {
                                override fun onImageSaved(outputFileResults: ImageCapture.OutputFileResults) {
                                    scope.launch {
                                        val compressed = withContext(Dispatchers.IO) {
                                            compressCapturedPhotoToWebp(tmpFile, isBoardMode).also {
                                                runCatching { tmpFile.delete() }
                                            }
                                        }
                                        isProcessing = false
                                        if (compressed != null) {
                                            capturedPair = compressed
                                        } else {
                                            cameraError = "Failed to compress photo"
                                        }
                                    }
                                }

                                override fun onError(exception: ImageCaptureException) {
                                    scope.launch {
                                        runCatching { tmpFile.delete() }
                                        isProcessing = false
                                        cameraError = exception.message ?: "Capture failed"
                                    }
                                }
                            },
                        )
                    },
                ) {
                    Icon(Icons.Rounded.CameraAlt, null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(strings.captureShutterDesc)
                }
            } else {
                Button(
                    onClick = {
                        val (prim, thumb) = capturedPair!!
                        onCaptured(prim, thumb, isPrivate, noteText.trim().ifBlank { null })
                    },
                ) { Text(strings.save) }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(strings.cancel) }
        },
    )
}

@Composable
private fun CameraXPhotoCapturePreview(
    lifecycleOwner: LifecycleOwner,
    onImageCaptureReady: (ImageCapture) -> Unit,
    onCameraError: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var cameraProviderRef by remember { mutableStateOf<ProcessCameraProvider?>(null) }

    DisposableEffect(Unit) {
        onDispose {
            runCatching { cameraProviderRef?.unbindAll() }
        }
    }

    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            val previewView = PreviewView(ctx).apply {
                scaleType = PreviewView.ScaleType.FILL_CENTER
            }
            val future = ProcessCameraProvider.getInstance(ctx)
            future.addListener(
                {
                    runCatching {
                        val provider = future.get()
                        cameraProviderRef = provider
                        val preview = Preview.Builder().build().also {
                            it.surfaceProvider = previewView.surfaceProvider
                        }
                        val capture = ImageCapture.Builder()
                            .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                            .build()
                        provider.unbindAll()
                        provider.bindToLifecycle(
                            lifecycleOwner,
                            CameraSelector.DEFAULT_BACK_CAMERA,
                            preview,
                            capture,
                        )
                        onImageCaptureReady(capture)
                    }.onFailure { err ->
                        onCameraError("无法启动相机：${err.message}")
                    }
                },
                ContextCompat.getMainExecutor(ctx),
            )
            previewView
        },
    )
}

@Composable
actual fun PlatformQrScannerDialog(
    onScanned: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val ctx = AndroidPlatformContext.appContext
    var hasPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED,
        )
    }
    var cameraError by remember { mutableStateOf<String?>(null) }
    val latestOnScanned by rememberUpdatedState(onScanned)

    val strings = com.saltnote.ui.i18n.LocalSaltStrings.current
    LaunchedEffect(Unit) {
        if (!hasPermission) {
            AndroidPlatformContext.cameraPermissionBridge?.invoke { granted ->
                hasPermission = granted
                if (!granted) {
                    cameraError = strings.cameraPermissionMissing
                }
            }
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(strings.cameraScanTitle) },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                if (hasPermission && AndroidPlatformContext.lifecycleOwner != null) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(280.dp)
                            .clip(RoundedCornerShape(14.dp)),
                    ) {
                        CameraXQrPreview(
                            lifecycleOwner = AndroidPlatformContext.lifecycleOwner!!,
                            onQrDecoded = { text -> latestOnScanned(text) },
                            onCameraError = { err -> cameraError = err },
                            modifier = Modifier.fillMaxSize(),
                        )
                        Surface(
                            color = Color.Black.copy(alpha = 0.45f),
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier
                                .align(Alignment.BottomCenter)
                                .padding(10.dp),
                        ) {
                            Text(
                                text = strings.cameraScanHint,
                                style = MaterialTheme.typography.labelMedium,
                                color = Color.White,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                            )
                        }
                    }
                } else {
                    Text(
                        text = cameraError ?: strings.cameraPermissionPrompt,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (!hasPermission) {
                        FilledTonalButton(
                            onClick = {
                                AndroidPlatformContext.cameraPermissionBridge?.invoke { granted ->
                                    hasPermission = granted
                                }
                            },
                        ) { Text(strings.grantCameraPermission) }
                    }
                }

                if (cameraError != null && hasPermission) {
                    Text(
                        text = cameraError!!,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                ) {
                    TextButton(
                        onClick = {
                            val clip = readTextFromClipboard()?.trim()
                            if (!clip.isNullOrBlank()) {
                                latestOnScanned(clip)
                            } else {
                                cameraError = strings.clipboardNoPairingUri
                            }
                        },
                    ) { Text(strings.pasteFromClipboard) }
                }
            }
        },
        confirmButton = {
            Button(onClick = onDismiss) { Text(strings.close) }
        },
    )
}

@Composable
private fun CameraXQrPreview(
    lifecycleOwner: LifecycleOwner,
    onQrDecoded: (String) -> Unit,
    onCameraError: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val analysisExecutor = remember { Executors.newSingleThreadExecutor() }
    val delivered = remember { AtomicBoolean(false) }
    val reader = remember {
        MultiFormatReader().apply {
            setHints(mapOf(DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE)))
        }
    }
    var cameraProviderRef by remember { mutableStateOf<ProcessCameraProvider?>(null) }

    DisposableEffect(Unit) {
        onDispose {
            runCatching { cameraProviderRef?.unbindAll() }
            analysisExecutor.shutdown()
        }
    }

    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            val previewView = PreviewView(ctx).apply {
                scaleType = PreviewView.ScaleType.FILL_CENTER
            }
            val future = ProcessCameraProvider.getInstance(ctx)
            future.addListener(
                {
                    runCatching {
                        val provider = future.get()
                        cameraProviderRef = provider
                        val preview = Preview.Builder().build().also {
                            it.surfaceProvider = previewView.surfaceProvider
                        }
                        val analysis = ImageAnalysis.Builder()
                            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                            .build()
                        analysis.setAnalyzer(analysisExecutor) { imageProxy ->
                            try {
                                if (delivered.get()) return@setAnalyzer
                                val plane = imageProxy.planes.firstOrNull() ?: return@setAnalyzer
                                val buf = plane.buffer
                                val w = imageProxy.width
                                val h = imageProxy.height
                                val rem = buf.remaining()
                                val bytes = ByteArray(maxOf(rem, plane.rowStride * h))
                                buf.get(bytes, 0, rem)
                                val source = PlanarYUVLuminanceSource(
                                    bytes,
                                    plane.rowStride,
                                    h,
                                    0,
                                    0,
                                    w.coerceAtMost(plane.rowStride),
                                    h,
                                    false,
                                )
                                val bitmap = BinaryBitmap(HybridBinarizer(source))
                                val result = runCatching { reader.decodeWithState(bitmap) }.getOrNull()
                                if (result != null && !result.text.isNullOrBlank()) {
                                    if (delivered.compareAndSet(false, true)) {
                                        ContextCompat.getMainExecutor(ctx).execute {
                                            onQrDecoded(result.text)
                                        }
                                    }
                                }
                            } finally {
                                reader.reset()
                                imageProxy.close()
                            }
                        }

                        provider.unbindAll()
                        provider.bindToLifecycle(
                            lifecycleOwner,
                            CameraSelector.DEFAULT_BACK_CAMERA,
                            preview,
                            analysis,
                        )
                    }.onFailure { err ->
                        onCameraError("无法启动相机：${err.message}")
                    }
                },
                ContextCompat.getMainExecutor(ctx),
            )
            previewView
        },
    )
}

private data class AndroidWetPoint(
    val x: Float,
    val y: Float,
    val pressure: Float,
)

/**
 * Low-latency Android native wet-ink SurfaceView with:
 * - GPU hardware-accelerated SurfaceView canvas (`lockHardwareCanvas` with software fallback)
 * - Exact 1:1 `StrokeGeometry` per-segment pressure-modulated quadratic Bezier rendering
 *   matching Compose dry ink pixel-for-pixel (eliminating width, alpha, and tail jumps!)
 * - 2-VSync Choreographer-aligned wet-buffer clear after synchronous optimistic dry-ink commit
 *   (eliminating wet->dry lift blink!)
 * - Hardware palm rejection & stylus eraser button support
 */
private class SaltWetInkSurfaceView(context: Context) : SurfaceView(context), SurfaceHolder.Callback {
    private val predictor: MotionEventPredictor = MotionEventPredictor.newInstance(this)
    private val segPath = Path()
    private val unifiedPath = Path()
    private val wetPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val collected = ArrayList<StrokeSample>(128)
    private val wetPoints = ArrayList<AndroidWetPoint>(128)
    private var surfaceReady = false
    private var lastEventTimeMs = 0L
    private var smoothedPressure: Float? = null

    // VSync-aligned deferred wet-buffer clear so dry ink is composited BEFORE wet clears
    private var pendingClearFrames = 0
    private var pendingClearCallback: Choreographer.FrameCallback? = null

    // Palm rejection & stylus tracking
    private var lastStylusActivityMs = 0L
    private var activeStrokePointerId = MotionEvent.INVALID_POINTER_ID
    private var activeStrokeIsStylus = false
    private var activeHardwareEraser = false

    // Two-finger pan/zoom state
    private var multiTouching = false
    private var lastMidX = 0f
    private var lastMidY = 0f
    private var lastSpan = 0f

    var toolKind: String = "pen"
    var strokeColorArgb: Int = 0xFF191C1E.toInt()
    var strokeWidthPx: Float = 3.2f
    var panX: Float = 0f
    var panY: Float = 0f
    var zoom: Float = 1f
    var stylusOnlyMode: Boolean = false
    var allowTouchScrollPassThrough: Boolean = false

    var onPanZoom: ((Float, Float, Float, Float, Float) -> Unit)? = null
    var onStrokeCompleted: ((List<StrokeSample>) -> Unit)? = null
    var onEraserMove: ((Float, Float) -> Unit)? = null

    init {
        setZOrderOnTop(true)
        holder.setFormat(PixelFormat.TRANSLUCENT)
        holder.addCallback(this)
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        surfaceReady = true
        clearWetBuffer()
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) = Unit

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        cancelPendingClear()
        surfaceReady = false
    }

    override fun onHoverEvent(event: MotionEvent): Boolean {
        val toolType = event.getToolType(0)
        if (toolType == MotionEvent.TOOL_TYPE_STYLUS || toolType == MotionEvent.TOOL_TYPE_ERASER) {
            lastStylusActivityMs = SystemClock.uptimeMillis()
            if (!activeStrokeIsStylus && collected.isNotEmpty()) {
                abortActiveStroke()
            }
        }
        return super.onHoverEvent(event)
    }

    private fun isStylusTool(event: MotionEvent, pointerIndex: Int = 0): Boolean {
        val tt = event.getToolType(pointerIndex)
        return tt == MotionEvent.TOOL_TYPE_STYLUS || tt == MotionEvent.TOOL_TYPE_ERASER
    }

    private fun isLikelyPalm(event: MotionEvent, pointerIndex: Int = 0): Boolean {
        val tt = event.getToolType(pointerIndex)
        if (tt == MotionEvent.TOOL_TYPE_STYLUS || tt == MotionEvent.TOOL_TYPE_ERASER) return false
        if (tt == 5) return true
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            (event.flags and MotionEvent.FLAG_CANCELED) != 0
        ) {
            return true
        }
        if (event.getTouchMajor(pointerIndex) > 48f || event.getSize(pointerIndex) > 0.14f) {
            return true
        }
        return false
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val action = event.actionMasked

        var stylusIndex = -1
        for (i in 0 until event.pointerCount) {
            if (isStylusTool(event, i)) {
                stylusIndex = i
                lastStylusActivityMs = SystemClock.uptimeMillis()
                break
            }
        }

        if (stylusIndex >= 0 && !activeStrokeIsStylus && collected.isNotEmpty()) {
            abortActiveStroke()
        }

        val nowMs = SystemClock.uptimeMillis()
        val recentStylusGuard = (nowMs - lastStylusActivityMs) < 1_200L

        if (stylusIndex < 0 && isLikelyPalm(event, 0)) {
            if (action == MotionEvent.ACTION_DOWN) abortActiveStroke()
            return !allowTouchScrollPassThrough
        }

        if (stylusIndex < 0) {
            if (stylusOnlyMode || recentStylusGuard) {
                if (allowTouchScrollPassThrough) {
                    parent?.requestDisallowInterceptTouchEvent(false)
                    return false
                }
                if (recentStylusGuard && event.pointerCount == 1) {
                    return true
                }
                parent?.requestDisallowInterceptTouchEvent(true)
                if (event.pointerCount >= 2) {
                    handleTwoFingerPanZoom(event)
                } else {
                    handleSingleFingerPan(event)
                }
                if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                    multiTouching = false
                    parent?.requestDisallowInterceptTouchEvent(false)
                }
                return true
            }

            if (event.pointerCount >= 2) {
                if (!allowTouchScrollPassThrough) {
                    parent?.requestDisallowInterceptTouchEvent(true)
                    handleTwoFingerPanZoom(event)
                    return true
                }
                parent?.requestDisallowInterceptTouchEvent(false)
                return false
            }

            if (multiTouching) {
                if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                    multiTouching = false
                    parent?.requestDisallowInterceptTouchEvent(false)
                }
                return true
            }
        }

        val ptrIdx = if (stylusIndex >= 0) stylusIndex else 0
        val ptrId = event.getPointerId(ptrIdx)

        if (action == MotionEvent.ACTION_DOWN || action == MotionEvent.ACTION_POINTER_DOWN) {
            parent?.requestDisallowInterceptTouchEvent(true)
        } else if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
            parent?.requestDisallowInterceptTouchEvent(false)
        }

        val isHardwareEraser = stylusIndex >= 0 && (
            event.getToolType(ptrIdx) == MotionEvent.TOOL_TYPE_ERASER ||
                (event.buttonState and MotionEvent.BUTTON_STYLUS_PRIMARY) != 0
            )

        predictor.record(event)

        if (toolKind == "select") {
            return false
        }

        if (toolKind == "pan" && !isHardwareEraser) {
            handleSingleFingerPan(event)
            return true
        }

        if (toolKind == "eraser" || isHardwareEraser || activeHardwareEraser) {
            handleEraser(event, ptrIdx, isHardwareEraser)
            return true
        }

        handleWetInkStroke(event, ptrIdx, ptrId, isStylus = stylusIndex >= 0)
        return true
    }

    private fun handleTwoFingerPanZoom(event: MotionEvent) {
        val midX = (event.getX(0) + event.getX(1)) * 0.5f
        val midY = (event.getY(0) + event.getY(1)) * 0.5f
        val span = hypot(
            (event.getX(0) - event.getX(1)).toDouble(),
            (event.getY(0) - event.getY(1)).toDouble(),
        ).toFloat().coerceAtLeast(1f)

        if (!multiTouching) {
            multiTouching = true
            abortActiveStroke()
            lastMidX = midX
            lastMidY = midY
            lastSpan = span
        } else {
            val dx = midX - lastMidX
            val dy = midY - lastMidY
            val scaleFactor = (span / lastSpan).coerceIn(0.85f, 1.15f)
            onPanZoom?.invoke(dx, dy, scaleFactor, midX, midY)
            lastMidX = midX
            lastMidY = midY
            lastSpan = span
        }
    }

    private fun handleSingleFingerPan(event: MotionEvent) {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                lastMidX = event.x
                lastMidY = event.y
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = event.x - lastMidX
                val dy = event.y - lastMidY
                lastMidX = event.x
                lastMidY = event.y
                onPanZoom?.invoke(dx, dy, 1f, event.x, event.y)
            }
        }
    }

    private fun handleEraser(event: MotionEvent, ptrIdx: Int, isHardwareEraser: Boolean) {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                activeHardwareEraser = isHardwareEraser
                abortActiveStroke()
                emitErase(event.getX(ptrIdx), event.getY(ptrIdx))
            }
            MotionEvent.ACTION_MOVE -> {
                for (h in 0 until event.historySize) {
                    emitErase(event.getHistoricalX(ptrIdx, h), event.getHistoricalY(ptrIdx, h))
                }
                emitErase(event.getX(ptrIdx), event.getY(ptrIdx))
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                activeHardwareEraser = false
            }
        }
    }

    private fun emitErase(screenX: Float, screenY: Float) {
        val wx = (screenX - panX) / zoom
        val wy = (screenY - panY) / zoom
        onEraserMove?.invoke(wx, wy)
    }

    private fun handleWetInkStroke(event: MotionEvent, ptrIdx: Int, ptrId: Int, isStylus: Boolean) {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                if (activeStrokePointerId != MotionEvent.INVALID_POINTER_ID && activeStrokeIsStylus && !isStylus) {
                    return
                }
                // Cancel any deferred clear from previous stroke immediately for rapid writing
                cancelPendingClear()
                activeStrokePointerId = ptrId
                activeStrokeIsStylus = isStylus
                collected.clear()
                wetPoints.clear()
                smoothedPressure = null
                lastEventTimeMs = event.eventTime
                appendSampleIfAccepted(
                    screenX = event.getX(ptrIdx),
                    screenY = event.getY(ptrIdx),
                    rawPressure = normalizeAndroidPressure(event.getPressure(ptrIdx), isStylus),
                    dtMs = 0,
                    forceAccept = true,
                )
                renderWetInk(predicted = null)
            }
            MotionEvent.ACTION_MOVE -> {
                val trackedIdx = event.findPointerIndex(activeStrokePointerId)
                if (trackedIdx < 0) return
                for (h in 0 until event.historySize) {
                    val hx = event.getHistoricalX(trackedIdx, h)
                    val hy = event.getHistoricalY(trackedIdx, h)
                    val hp = normalizeAndroidPressure(event.getHistoricalPressure(trackedIdx, h), activeStrokeIsStylus)
                    val ht = event.getHistoricalEventTime(h)
                    val dt = (ht - lastEventTimeMs).coerceAtLeast(1L).toInt()
                    lastEventTimeMs = ht
                    appendSampleIfAccepted(hx, hy, hp, dt)
                }
                val sx = event.getX(trackedIdx)
                val sy = event.getY(trackedIdx)
                val sp = normalizeAndroidPressure(event.getPressure(trackedIdx), activeStrokeIsStylus)
                val dt = (event.eventTime - lastEventTimeMs).coerceAtLeast(1L).toInt()
                lastEventTimeMs = event.eventTime
                appendSampleIfAccepted(sx, sy, sp, dt)

                val predicted = predictor.predict()
                renderWetInk(predicted)
                predicted?.recycle()
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                val upId = event.getPointerId(event.actionIndex)
                if (upId != activeStrokePointerId && event.actionMasked == MotionEvent.ACTION_POINTER_UP) {
                    return
                }
                val trackedIdx = event.findPointerIndex(activeStrokePointerId).takeIf { it >= 0 } ?: ptrIdx
                val sx = event.getX(trackedIdx)
                val sy = event.getY(trackedIdx)
                val sp = normalizeAndroidPressure(event.getPressure(trackedIdx), activeStrokeIsStylus)
                val dt = (event.eventTime - lastEventTimeMs).coerceAtLeast(1L).toInt()
                appendSampleIfAccepted(sx, sy, sp, dt)

                // Re-render final locked contour (without predicted tail) BEFORE optimistic dry-ink commit
                renderWetInk(predicted = null)
                val finished = ArrayList(collected)

                activeStrokePointerId = MotionEvent.INVALID_POINTER_ID
                activeStrokeIsStylus = false
                collected.clear()
                wetPoints.clear()
                smoothedPressure = null

                if (finished.isNotEmpty()) {
                    // Synchronously commit optimistic dry stroke into Compose state
                    onStrokeCompleted?.invoke(finished)
                    // Clear wet SurfaceView AFTER 2 VSync frames so Compose has already composited dry ink
                    if (StrokeGeometry.isHalo(toolKind)) {
                        clearWetBuffer()
                    } else {
                        scheduleClearWetBufferAfterVSync()
                    }
                } else {
                    clearWetBuffer()
                }
            }
            MotionEvent.ACTION_CANCEL -> {
                abortActiveStroke()
            }
        }
    }

    private fun normalizeAndroidPressure(raw: Float, isStylus: Boolean): Float {
        if (!isStylus && (raw <= 0.01f || raw >= 0.99f)) {
            return 0.65f
        }
        return raw.coerceIn(StrokeGeometry.PRESSURE_MIN, StrokeGeometry.PRESSURE_MAX)
    }

    private fun appendSampleIfAccepted(
        screenX: Float,
        screenY: Float,
        rawPressure: Float,
        dtMs: Int,
        forceAccept: Boolean = false,
    ): Boolean {
        val wx = (screenX - panX) / zoom
        val wy = (screenY - panY) / zoom
        val last = collected.lastOrNull()
        if (!forceAccept && last != null) {
            val d = hypot((wx - last.x).toDouble(), (wy - last.y).toDouble())
            if (d < 0.35) return false
        }
        val smoothP = StrokeGeometry.smoothPressure(smoothedPressure, rawPressure)
        smoothedPressure = smoothP
        collected.add(StrokeSample(wx, wy, smoothP, dtMs))
        wetPoints.add(AndroidWetPoint(screenX, screenY, smoothP))
        return true
    }

    private fun cancelPendingClear() {
        pendingClearCallback?.let { cb ->
            Choreographer.getInstance().removeFrameCallback(cb)
        }
        pendingClearCallback = null
        pendingClearFrames = 0
    }

    private fun scheduleClearWetBufferAfterVSync() {
        cancelPendingClear()
        pendingClearFrames = 2
        val cb = object : Choreographer.FrameCallback {
            override fun doFrame(frameTimeNanos: Long) {
                if (activeStrokePointerId != MotionEvent.INVALID_POINTER_ID) {
                    pendingClearCallback = null
                    return
                }
                pendingClearFrames--
                if (pendingClearFrames <= 0) {
                    pendingClearCallback = null
                    clearWetBuffer()
                } else {
                    Choreographer.getInstance().postFrameCallback(this)
                }
            }
        }
        pendingClearCallback = cb
        Choreographer.getInstance().postFrameCallback(cb)
    }

    private fun abortActiveStroke() {
        cancelPendingClear()
        activeStrokePointerId = MotionEvent.INVALID_POINTER_ID
        activeStrokeIsStylus = false
        collected.clear()
        wetPoints.clear()
        smoothedPressure = null
        clearWetBuffer()
    }

    private fun lockFastCanvas(): AndroidCanvas? =
        runCatching { holder.lockHardwareCanvas() }.getOrNull() ?: holder.lockCanvas()

    private fun renderWetInk(predicted: MotionEvent?) {
        if (!surfaceReady) return
        val canvas = lockFastCanvas() ?: return
        try {
            canvas.drawColor(0, PorterDuff.Mode.CLEAR)
            val count = wetPoints.size
            if (count == 0) return

            val isHighlighter = StrokeGeometry.isHighlighter(toolKind)
            val isHalo = StrokeGeometry.isHalo(toolKind)
            val effectiveAlpha = if (isHighlighter) StrokeGeometry.HIGHLIGHTER_ALPHA_INT else 255

            if (count == 1) {
                val p0 = wetPoints[0]
                dotPaint.color = strokeColorArgb
                dotPaint.alpha = effectiveAlpha
                val r = StrokeGeometry.dotRadius(toolKind, strokeWidthPx, p0.pressure) * zoom
                canvas.drawCircle(p0.x, p0.y, r, dotPaint)
                return
            }

            wetPaint.color = strokeColorArgb
            wetPaint.alpha = effectiveAlpha
            wetPaint.pathEffect = if (isHalo) DashPathEffect(floatArrayOf(18f, 12f), 0f) else null

            if (isHighlighter || isHalo) {
                unifiedPath.reset()
                unifiedPath.moveTo(wetPoints[0].x, wetPoints[0].y)
                if (count == 2) {
                    unifiedPath.lineTo(wetPoints[1].x, wetPoints[1].y)
                } else {
                    for (i in 1 until count - 1) {
                        val cur = wetPoints[i]
                        val next = wetPoints[i + 1]
                        unifiedPath.quadTo(cur.x, cur.y, (cur.x + next.x) * 0.5f, (cur.y + next.y) * 0.5f)
                    }
                    val last = wetPoints[count - 1]
                    unifiedPath.lineTo(last.x, last.y)
                }
                wetPaint.strokeWidth = StrokeGeometry.worldWidth(toolKind, strokeWidthPx, 0.65f) * zoom
                canvas.drawPath(unifiedPath, wetPaint)
            } else {
                // Exact 1:1 midpoint quadratic-Bezier per-segment pressure rendering matching StrokeRenderer.kt
                var prevMidX = wetPoints[0].x
                var prevMidY = wetPoints[0].y
                for (i in 1 until count) {
                    val prev = wetPoints[i - 1]
                    val cur = wetPoints[i]
                    val midX = if (i == count - 1) cur.x else (prev.x + cur.x) * 0.5f
                    val midY = if (i == count - 1) cur.y else (prev.y + cur.y) * 0.5f
                    val avgP = (prev.pressure + cur.pressure) * 0.5f
                    wetPaint.strokeWidth = StrokeGeometry.worldWidth(toolKind, strokeWidthPx, avgP) * zoom

                    segPath.reset()
                    segPath.moveTo(prevMidX, prevMidY)
                    segPath.quadTo(prev.x, prev.y, midX, midY)
                    canvas.drawPath(segPath, wetPaint)
                    prevMidX = midX
                    prevMidY = midY
                }
            }

            if (predicted != null && predicted.pointerCount > 0) {
                val last = wetPoints[count - 1]
                val predDist = hypot((predicted.x - last.x).toDouble(), (predicted.y - last.y).toDouble())
                // Cap prediction tail to prevent sharp-corner overshoot jumps
                if (predDist in 1.0..36.0) {
                    wetPaint.strokeWidth = StrokeGeometry.worldWidth(toolKind, strokeWidthPx, last.pressure) * zoom
                    canvas.drawLine(last.x, last.y, predicted.x, predicted.y, wetPaint)
                }
            }
        } finally {
            holder.unlockCanvasAndPost(canvas)
        }
    }

    private fun clearWetBuffer() {
        if (!surfaceReady) return
        val canvas = lockFastCanvas() ?: return
        try {
            canvas.drawColor(0, PorterDuff.Mode.CLEAR)
        } finally {
            holder.unlockCanvasAndPost(canvas)
        }
    }
}

@Composable
actual fun PlatformInkSurface(
    modifier: Modifier,
    toolKind: String,
    strokeColor: Color,
    strokeWidth: Float,
    panX: Float,
    panY: Float,
    zoom: Float,
    stylusOnlyMode: Boolean,
    allowTouchScrollPassThrough: Boolean,
    onPanZoom: (dx: Float, dy: Float, zoomFactor: Float, pivotX: Float, pivotY: Float) -> Unit,
    onStrokeCompleted: (worldPoints: List<StrokeSample>) -> Unit,
    onEraserMove: (worldX: Float, worldY: Float) -> Unit,
) {
    val latestOnPanZoom by rememberUpdatedState(onPanZoom)
    val latestOnStroke by rememberUpdatedState(onStrokeCompleted)
    val latestOnErase by rememberUpdatedState(onEraserMove)

    AndroidView(
        modifier = modifier.fillMaxSize(),
        factory = { ctx -> SaltWetInkSurfaceView(ctx) },
        update = { view ->
            view.toolKind = toolKind
            view.strokeColorArgb = strokeColor.toArgb()
            view.strokeWidthPx = strokeWidth
            view.panX = panX
            view.panY = panY
            view.zoom = zoom
            view.stylusOnlyMode = stylusOnlyMode
            view.allowTouchScrollPassThrough = allowTouchScrollPassThrough
            view.onPanZoom = latestOnPanZoom
            view.onStrokeCompleted = latestOnStroke
            view.onEraserMove = latestOnErase
        },
    )
}
