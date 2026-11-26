package com.saltnote.android

import android.content.Context
import android.content.Intent
import com.saltnote.bridge.AndroidSecretVault
import com.saltnote.bridge.SaltNoteClient
import com.saltnote.bridge.openSaltNoteClient
import com.saltnote.ui.platform.AndroidPlatformContext
import com.saltnote.ui.platform.currentNetworkState
import com.saltnote.ui.platform.defaultDeviceName
import com.saltnote.ui.platform.defaultVaultDirectory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Process-wide singleton holder for [SaltNoteClient] so [MainActivity], [ShareIngestActivity],
 * and [SaltInboxWidget] operate on one shared Rust `SaltEngine` instance without concurrent
 * WAL / SQLite contention.
 */
object SaltClientRegistry {
    const val EXTRA_LAUNCH_ACTION = "com.saltnote.android.LAUNCH_ACTION"
    const val ACTION_CAPTURE_CAMERA = "camera"
    const val ACTION_CAPTURE_VOICE = "voice"
    const val ACTION_MICRO_REVIEW = "micro_review"

    @Volatile
    private var activeClient: SaltNoteClient? = null

    private val _ingestTick = MutableStateFlow(0L)
    val ingestTick: StateFlow<Long> = _ingestTick.asStateFlow()

    val isMainActivityActive = AtomicBoolean(false)
    private val backgroundScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun triggerBestEffortBackgroundSync(context: Context) {
        val appCtx = context.applicationContext
        backgroundScope.launch {
            runCatching {
                withTimeoutOrNull(8_000L) {
                    val client = getOrOpen(appCtx)
                    val prefs = client.getSyncPrefs()
                    if (!prefs.autoSyncOnCapture) return@withTimeoutOrNull
                    val net = currentNetworkState()
                    if (!net.isOnline) return@withTimeoutOrNull
                    client.flushDeltas()

                    var lanSucceeded = false
                    if (net.isWifiOrEthernet && client.listPairedPeers().any { it.autoSync }) {
                        val report = runCatching { client.syncAllPairedLanPeers() }.getOrNull()
                        lanSucceeded = (report?.succeededPeers ?: 0) > 0
                    }
                    val davCfg = client.getWebDavConfig()
                    if (davCfg.enabled &&
                        prefs.autoSyncWebdav &&
                        (!lanSucceeded || net.isMetered || prefs.mirrorWebdavAfterLan)
                    ) {
                        runCatching { client.syncWebDavNow(isMetered = net.isMetered) }
                    }
                }
            }
        }
    }

    @Synchronized
    fun getOrOpen(context: Context): SaltNoteClient {
        val appCtx = context.applicationContext
        if (!AndroidPlatformContext.isInitialized()) {
            AndroidPlatformContext.appContext = appCtx
        }
        val targetVault = defaultVaultDirectory()
        val existing = activeClient
        if (existing != null && existing.vaultPath == targetVault) {
            return existing
        }
        val opened = openSaltNoteClient(
            vaultPath = targetVault,
            deviceName = defaultDeviceName(),
            secretVault = AndroidSecretVault(appCtx),
        )
        activeClient = opened
        return opened
    }

    @Synchronized
    fun updateActiveClient(client: SaltNoteClient) {
        activeClient = client
    }

    fun notifyIngested() {
        _ingestTick.value += 1L
    }

    fun buildLaunchMainIntent(context: Context, launchAction: String): Intent =
        Intent(context, MainActivity::class.java).apply {
            action = "com.saltnote.android.LAUNCH_$launchAction"
            putExtra(EXTRA_LAUNCH_ACTION, launchAction)
            addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP,
            )
        }
}
