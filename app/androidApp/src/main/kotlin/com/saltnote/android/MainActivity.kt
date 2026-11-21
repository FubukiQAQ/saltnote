package com.saltnote.android

import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import com.saltnote.bridge.AndroidSecretVault
import com.saltnote.bridge.openSaltNoteClient
import com.saltnote.ui.SaltNoteApp
import com.saltnote.ui.platform.AndroidPlatformContext
import com.saltnote.ui.platform.defaultDeviceName
import com.saltnote.ui.platform.defaultVaultDirectory
import com.saltnote.ui.state.SaltAppState

class MainActivity : ComponentActivity() {
    private var appState: SaltAppState? = null

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
        AndroidPlatformContext.imagePickerBridge = {
            imagePickerLauncher.launch("image/*")
            null
        }

        val vaultPath = defaultVaultDirectory()
        val deviceName = defaultDeviceName()
        val client = openSaltNoteClient(
            vaultPath = vaultPath,
            deviceName = deviceName,
            secretVault = AndroidSecretVault(applicationContext),
        )
        val state = SaltAppState(initialClient = client, deviceName = deviceName)
        appState = state

        setContent {
            SaltNoteApp(state = state)
        }
    }

    override fun onPause() {
        super.onPause()
        runCatching { appState?.client?.flushDeltas() }
    }

    override fun onDestroy() {
        super.onDestroy()
        runCatching { appState?.client?.stopLanP2p() }
    }
}
