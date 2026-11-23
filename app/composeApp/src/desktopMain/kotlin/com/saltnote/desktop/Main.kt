package com.saltnote.desktop

import androidx.compose.runtime.remember
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.saltnote.bridge.DesktopSecretVault
import com.saltnote.bridge.openSaltNoteClient
import com.saltnote.ui.SaltNoteApp
import com.saltnote.ui.platform.defaultDeviceName
import com.saltnote.ui.platform.defaultVaultDirectory
import com.saltnote.ui.state.SaltAppState

fun main() = application {
    val windowState = rememberWindowState(size = DpSize(1280.dp, 820.dp))
    val appState = remember {
        val vaultDir = defaultVaultDirectory()
        val deviceName = defaultDeviceName()
        val client = openSaltNoteClient(
            vaultPath = vaultDir,
            deviceName = deviceName,
            secretVault = DesktopSecretVault(),
        )
        SaltAppState(initialClient = client, deviceName = deviceName)
    }

    Window(
        onCloseRequest = {
            appState.dispose()
            runCatching { appState.client.flushDeltas() }
            runCatching { appState.client.stopLanP2p() }
            exitApplication()
        },
        state = windowState,
        title = "SaltNote",
    ) {
        SaltNoteApp(state = appState)
    }
}
