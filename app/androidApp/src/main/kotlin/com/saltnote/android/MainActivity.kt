package com.saltnote.android

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.LaunchedEffect
import androidx.glance.appwidget.updateAll
import androidx.lifecycle.lifecycleScope
import com.saltnote.ui.SaltNoteApp
import com.saltnote.ui.platform.AndroidPlatformContext
import com.saltnote.ui.platform.defaultDeviceName
import com.saltnote.ui.state.LaunchRoute
import com.saltnote.ui.state.SaltAppState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private var appState: SaltAppState? = null
    private var pendingCameraPermissionCallback: ((Boolean) -> Unit)? = null
    private var pendingMicPermissionCallback: ((Boolean) -> Unit)? = null
    private var pendingFolderTreeCallback: ((Uri?) -> Unit)? = null

    private val cameraPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            val cb = pendingCameraPermissionCallback
            pendingCameraPermissionCallback = null
            cb?.invoke(granted)
        }

    private val micPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            val cb = pendingMicPermissionCallback
            pendingMicPermissionCallback = null
            cb?.invoke(granted)
        }

    private val folderTreePickerLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri: Uri? ->
            val cb = pendingFolderTreeCallback
            pendingFolderTreeCallback = null
            cb?.invoke(uri)
        }

    private val imagePickerLauncher =
        registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
            if (uri != null) {
                val bytes = runCatching {
                    contentResolver.openInputStream(uri)?.use { it.readBytes() }
                }.getOrNull()
                if (bytes != null) {
                    appState?.insertImageToActiveCanvas(bytes)
                }
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        AndroidPlatformContext.appContext = applicationContext
        AndroidPlatformContext.lifecycleOwner = this
        AndroidPlatformContext.imagePickerBridge = {
            imagePickerLauncher.launch("image/*")
            null
        }
        AndroidPlatformContext.cameraPermissionBridge = { onResult ->
            pendingCameraPermissionCallback = onResult
            cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
        }
        AndroidPlatformContext.micPermissionBridge = { onResult ->
            pendingMicPermissionCallback = onResult
            micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
        AndroidPlatformContext.folderTreePickerBridge = { onResult ->
            pendingFolderTreeCallback = onResult
            folderTreePickerLauncher.launch(null)
        }

        val deviceName = defaultDeviceName()
        val client = SaltClientRegistry.getOrOpen(applicationContext)
        val state = SaltAppState(initialClient = client, deviceName = deviceName)
        appState = state
        handleLaunchIntent(intent, state)

        lifecycleScope.launch {
            var lastSeenTick = SaltClientRegistry.ingestTick.value
            SaltClientRegistry.ingestTick.collectLatest { tick ->
                if (tick != lastSeenTick) {
                    lastSeenTick = tick
                    appState?.onExternalVaultMutated()
                }
            }
        }

        setContent {
            LaunchedEffect(state.client) {
                SaltClientRegistry.updateActiveClient(state.client)
            }
            SaltNoteApp(state = state)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        appState?.let { handleLaunchIntent(intent, it) }
    }

    private fun handleLaunchIntent(intent: Intent?, state: SaltAppState) {
        val action = intent?.getStringExtra(SaltClientRegistry.EXTRA_LAUNCH_ACTION) ?: return
        state.pendingLaunchRoute = when (action) {
            SaltClientRegistry.ACTION_CAPTURE_CAMERA -> LaunchRoute.CameraCapture
            SaltClientRegistry.ACTION_CAPTURE_VOICE -> LaunchRoute.VoiceCapture
            SaltClientRegistry.ACTION_MICRO_REVIEW -> LaunchRoute.MicroReview
            else -> LaunchRoute.None
        }
        intent.removeExtra(SaltClientRegistry.EXTRA_LAUNCH_ACTION)
    }

    override fun onResume() {
        super.onResume()
        SaltClientRegistry.isMainActivityActive.set(true)
        appState?.onAppResumed()
    }

    override fun onPause() {
        SaltClientRegistry.isMainActivityActive.set(false)
        super.onPause()
        runCatching { appState?.client?.flushDeltas() }
        lifecycleScope.launch(Dispatchers.IO) {
            runCatching { SaltInboxWidget().updateAll(applicationContext) }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        SaltClientRegistry.isMainActivityActive.set(false)
        appState?.dispose()
    }
}
