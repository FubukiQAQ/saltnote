package com.saltnote.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Archive
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.CloudSync
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.ContentPaste
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Devices
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.QrCodeScanner
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.SignalCellularAlt
import androidx.compose.material.icons.rounded.Wifi
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.saltnote.bridge.LanPeerInfoDto
import com.saltnote.bridge.WebDavConfigDto
import com.saltnote.ui.components.AutoSyncStatusPill
import com.saltnote.ui.components.LanPairingCodec
import com.saltnote.ui.components.SaltQrCodeView
import com.saltnote.ui.i18n.LocalSaltStrings
import com.saltnote.ui.platform.PlatformQrScannerDialog
import com.saltnote.ui.platform.copyTextToClipboard
import com.saltnote.ui.platform.exportMarkdownBackupWithPicker
import com.saltnote.ui.platform.formatUtcTimestamp
import com.saltnote.ui.platform.readTextFromClipboard
import com.saltnote.ui.platform.supportsLiveCameraQrScan
import com.saltnote.ui.state.SaltAppState

@Composable
fun SyncScreen(state: SaltAppState) {
    val strings = LocalSaltStrings.current
    var endpoint by remember(state.webDavConfig) { mutableStateOf(state.webDavConfig.endpoint) }
    var rootPath by remember(state.webDavConfig) { mutableStateOf(state.webDavConfig.rootPath) }
    var username by remember(state.webDavConfig) { mutableStateOf(state.webDavConfig.username) }
    var passwordInput by remember { mutableStateOf("") }
    var webDavEnabled by remember(state.webDavConfig) { mutableStateOf(state.webDavConfig.enabled) }

    var manualHost by remember { mutableStateOf("") }
    var manualPort by remember { mutableStateOf("47821") }
    var manualFp by remember { mutableStateOf("") }
    var manualPin by remember { mutableStateOf("") }
    var showQrScannerDialog by remember { mutableStateOf(false) }
    var pendingPairPeer by remember { mutableStateOf<LanPeerInfoDto?>(null) }
    var pairPinInput by remember { mutableStateOf("") }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(strings.syncTitle, style = MaterialTheme.typography.headlineMedium)
            AutoSyncStatusPill(
                status = state.autoSyncStatus,
                onClick = { state.triggerManualAutoSync() },
            )
        }

        // Metered vs Wi-Fi Sync Guard Status Banner (v0.2-alpha Section 4.4)
        Surface(
            color = if (state.isMeteredNetwork) {
                MaterialTheme.colorScheme.tertiaryContainer
            } else {
                MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.5f)
            },
            shape = MaterialTheme.shapes.medium,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Icon(
                    imageVector = if (state.isMeteredNetwork) Icons.Rounded.SignalCellularAlt else Icons.Rounded.Wifi,
                    contentDescription = null,
                    tint = if (state.isMeteredNetwork) {
                        MaterialTheme.colorScheme.onTertiaryContainer
                    } else {
                        MaterialTheme.colorScheme.primary
                    },
                )
                Text(
                    text = if (state.isMeteredNetwork) {
                        strings.meteredNetworkWarning
                    } else {
                        strings.unmeteredNetworkBanner
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = if (state.isMeteredNetwork) {
                        MaterialTheme.colorScheme.onTertiaryContainer
                    } else {
                        MaterialTheme.colorScheme.onSecondaryContainer
                    },
                )
            }
        }

        // Card 0: Auto-Sync Policy
        ElevatedCard(modifier = Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.large) {
            Column(
                modifier = Modifier.padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(strings.autoSyncSettingsTitle, style = MaterialTheme.typography.titleMedium)
                SyncPrefSwitchRow(
                    label = strings.lanAutoStartLabel,
                    checked = state.syncPrefs.lanAutoStart,
                    onCheckedChange = { state.saveSyncPrefs(state.syncPrefs.copy(lanAutoStart = it)) },
                )
                SyncPrefSwitchRow(
                    label = strings.autoSyncCaptureLabel,
                    checked = state.syncPrefs.autoSyncOnCapture,
                    onCheckedChange = { state.saveSyncPrefs(state.syncPrefs.copy(autoSyncOnCapture = it)) },
                )
                SyncPrefSwitchRow(
                    label = strings.autoSyncEditLabel,
                    checked = state.syncPrefs.autoSyncOnEdit,
                    onCheckedChange = { state.saveSyncPrefs(state.syncPrefs.copy(autoSyncOnEdit = it)) },
                )
                SyncPrefSwitchRow(
                    label = strings.autoSyncWebdavLabel,
                    checked = state.syncPrefs.autoSyncWebdav,
                    onCheckedChange = { state.saveSyncPrefs(state.syncPrefs.copy(autoSyncWebdav = it)) },
                )
                if (webDavEnabled) {
                    SyncPrefSwitchRow(
                        label = strings.mirrorWebdavAfterLanLabel,
                        checked = state.syncPrefs.mirrorWebdavAfterLan,
                        onCheckedChange = { state.saveSyncPrefs(state.syncPrefs.copy(mirrorWebdavAfterLan = it)) },
                    )
                }
            }
        }

        // Card 1: WebDAV Sync
        ElevatedCard(modifier = Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.large) {
            Column(
                modifier = Modifier.padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Rounded.CloudSync, null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(10.dp))
                    Text(strings.webdavSync, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                    Switch(checked = webDavEnabled, onCheckedChange = { webDavEnabled = it })
                }

                OutlinedTextField(
                    value = endpoint,
                    onValueChange = { endpoint = it },
                    label = { Text(strings.serverUrlLabel) },
                    placeholder = { Text("https://dav.jianguoyun.com/dav/") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    OutlinedTextField(
                        value = rootPath,
                        onValueChange = { rootPath = it },
                        label = { Text(strings.remoteDirLabel) },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                    OutlinedTextField(
                        value = username,
                        onValueChange = { username = it },
                        label = { Text(strings.usernameLabel) },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                }
                val hasSaved = state.client.secretVault.hasSecret(state.webDavConfig.passwordKeyRef)
                OutlinedTextField(
                    value = passwordInput,
                    onValueChange = { passwordInput = it },
                    label = { Text(strings.passwordLabel) },
                    placeholder = if (hasSaved) {
                        { Text(strings.savedPlaceholder) }
                    } else {
                        null
                    },
                    visualTransformation = PasswordVisualTransformation(),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )

                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    FilledTonalButton(
                        onClick = {
                            state.saveWebDavConfig(
                                WebDavConfigDto(
                                    endpoint = endpoint.trim(),
                                    rootPath = rootPath.trim().ifEmpty { "/SaltNoteVault" },
                                    username = username.trim(),
                                    passwordKeyRef = "vault://webdav_password",
                                    enabled = webDavEnabled,
                                ),
                                optionalPassword = passwordInput.takeIf { it.isNotBlank() },
                            )
                            passwordInput = ""
                        },
                    ) { Text(strings.saveConfig) }

                    Button(
                        onClick = { state.triggerWebDavSync() },
                        enabled = !state.isSyncingWebDav && webDavEnabled,
                    ) {
                        if (state.isSyncingWebDav) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.onPrimary,
                            )
                            Spacer(Modifier.width(8.dp))
                        }
                        Text(strings.syncNow)
                    }
                }

                state.lastSyncReport?.let { r ->
                    Surface(
                        color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.5f),
                        shape = MaterialTheme.shapes.small,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Column(
                            modifier = Modifier.padding(10.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            Text(
                                text = "${strings.lastSyncPrefix}${strings.uploadedLabel} ${r.uploadedDeltas} · ${strings.downloadedLabel} ${r.downloadedDeltas}",
                                style = MaterialTheme.typography.bodySmall,
                            )
                            if (r.deferredAssets > 0) {
                                Text(
                                    text = "${strings.deferredAssetsPrefix}${r.deferredAssets}",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.tertiary,
                                )
                            }
                        }
                    }
                }
            }
        }

        // Card 2: LAN Direct Sync & QR Pairing
        ElevatedCard(modifier = Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.large) {
            Column(
                modifier = Modifier.padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Rounded.Wifi, null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(10.dp))
                    Text(strings.lanSyncTitle, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                    Switch(
                        checked = state.lanNodeInfo != null,
                        onCheckedChange = { state.toggleLanP2p(it) },
                    )
                }

                state.lanNodeInfo?.let { node ->
                    val pairingUri = remember(node, state.deviceState.deviceName) {
                        LanPairingCodec.encode(
                            host = node.hostIp.ifBlank { "127.0.0.1" },
                            port = node.port,
                            fingerprint = node.fingerprint,
                            deviceName = state.deviceState.deviceName,
                            token = node.pairingToken,
                            deviceId = node.deviceId,
                        )
                    }

                    OutlinedCard(modifier = Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.medium) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(14.dp),
                            horizontalArrangement = Arrangement.spacedBy(16.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            SaltQrCodeView(
                                payload = pairingUri,
                                size = 148.dp,
                            )
                            Column(
                                modifier = Modifier.weight(1f),
                                verticalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                Text(
                                    text = strings.localQrTitle,
                                    style = MaterialTheme.typography.titleSmall,
                                )
                                Text(
                                    text = "${strings.addressLabel}: ${node.hostIp.ifBlank { "…" }}:${node.port}",
                                    style = MaterialTheme.typography.labelLarge,
                                )
                                Text(
                                    text = "${strings.fingerprintLabel}: ${node.fingerprint.take(24)}…",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                if (node.pairingPin.isNotBlank()) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                                    ) {
                                        Surface(
                                            color = MaterialTheme.colorScheme.secondaryContainer,
                                            shape = RoundedCornerShape(8.dp),
                                        ) {
                                            Text(
                                                text = "${strings.pairingPinLabel}: ${node.pairingPin}",
                                                style = MaterialTheme.typography.labelLarge,
                                                fontWeight = FontWeight.SemiBold,
                                                color = MaterialTheme.colorScheme.onSecondaryContainer,
                                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                                            )
                                        }
                                        IconButton(
                                            onClick = { state.rotateLanPairingInfo() },
                                            modifier = Modifier.size(28.dp),
                                        ) {
                                            Icon(
                                                Icons.Rounded.Refresh,
                                                contentDescription = strings.refreshPinDesc,
                                                modifier = Modifier.size(16.dp),
                                            )
                                        }
                                    }
                                }
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    FilledTonalButton(
                                        onClick = {
                                            copyTextToClipboard(pairingUri)
                                            state.notifyMessage(strings.pairingCopiedToast)
                                        },
                                    ) {
                                        Icon(Icons.Rounded.ContentCopy, null, modifier = Modifier.size(16.dp))
                                        Spacer(Modifier.width(6.dp))
                                        Text(strings.copyPairingCode)
                                    }
                                    Button(
                                        onClick = { showQrScannerDialog = true },
                                        enabled = !state.isSyncingLan,
                                    ) {
                                        Icon(Icons.Rounded.QrCodeScanner, null, modifier = Modifier.size(16.dp))
                                        Spacer(Modifier.width(6.dp))
                                        Text(if (supportsLiveCameraQrScan()) strings.scanPair else strings.importPairCode)
                                    }
                                }
                            }
                        }
                    }
                }

                // Trusted mTLS Paired Devices Section
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Icon(
                            Icons.Rounded.Lock,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                            tint = MaterialTheme.colorScheme.primary,
                        )
                        Text(
                            "${strings.pairedDevicesLabel} (${state.pairedPeers.size})",
                            style = MaterialTheme.typography.titleSmall,
                        )
                    }
                    if (state.lanNodeInfo != null) {
                        IconButton(onClick = { state.refreshLanPeers() }) {
                            Icon(Icons.Rounded.Refresh, strings.refreshDesc)
                        }
                    }
                }

                if (state.pairedPeers.isEmpty()) {
                    Text(
                        text = strings.noPairedDevicesHint,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    state.pairedPeers.forEach { paired ->
                        val livePeer = state.lanPeers.firstOrNull {
                            it.deviceId == paired.deviceId ||
                                it.certFingerprint.equals(paired.certFingerprint, ignoreCase = true)
                        }
                        val isOnline = livePeer != null
                        val targetHost = livePeer?.host ?: paired.lastHost
                        val targetPort = livePeer?.port ?: paired.lastPort

                        OutlinedCard(modifier = Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.small) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(9.dp)
                                        .clip(CircleShape)
                                        .background(
                                            if (isOnline) Color(0xFF2E7D32) else MaterialTheme.colorScheme.outline,
                                        ),
                                )
                                Spacer(Modifier.width(8.dp))
                                Icon(
                                    Icons.Rounded.Devices,
                                    null,
                                    tint = if (isOnline) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                Spacer(Modifier.width(10.dp))
                                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                                    ) {
                                        Text(paired.deviceName, style = MaterialTheme.typography.titleSmall)
                                        Surface(
                                            color = if (isOnline) {
                                                Color(0xFF2E7D32).copy(alpha = 0.14f)
                                            } else {
                                                MaterialTheme.colorScheme.surfaceContainerHighest
                                            },
                                            shape = RoundedCornerShape(50),
                                        ) {
                                            Text(
                                                text = if (isOnline) strings.onlineLabel else strings.offlineLabel,
                                                style = MaterialTheme.typography.labelSmall,
                                                color = if (isOnline) Color(0xFF2E7D32) else MaterialTheme.colorScheme.onSurfaceVariant,
                                                modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp),
                                            )
                                        }
                                        if (livePeer?.relayAvailable == true) {
                                            Surface(
                                                color = MaterialTheme.colorScheme.primaryContainer,
                                                shape = RoundedCornerShape(50),
                                            ) {
                                                Row(
                                                    modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp),
                                                    verticalAlignment = Alignment.CenterVertically,
                                                    horizontalArrangement = Arrangement.spacedBy(3.dp),
                                                ) {
                                                    Icon(
                                                        Icons.Rounded.Bolt,
                                                        contentDescription = null,
                                                        modifier = Modifier.size(12.dp),
                                                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                                                    )
                                                    Text(
                                                        text = strings.relayBadgeLabel,
                                                        style = MaterialTheme.typography.labelSmall,
                                                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                                                    )
                                                }
                                            }
                                        }
                                    }
                                    Text(
                                        text = "$targetHost:$targetPort · ${strings.lastSyncPrefix}${formatUtcTimestamp(paired.lastSyncUtc)}",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                Switch(
                                    checked = paired.autoSync,
                                    onCheckedChange = { state.setPairedPeerAutoSync(paired.deviceId, it) },
                                )
                                Spacer(Modifier.width(6.dp))
                                Button(
                                    onClick = {
                                        state.syncWithLanPeer(targetHost, targetPort, paired.certFingerprint)
                                    },
                                    enabled = !state.isSyncingLan && targetHost.isNotBlank(),
                                ) {
                                    Text(strings.syncAction)
                                }
                                IconButton(
                                    onClick = { state.removePairedPeer(paired.deviceId) },
                                ) {
                                    Icon(
                                        Icons.Rounded.DeleteOutline,
                                        contentDescription = strings.unpairAction,
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                    }
                }

                state.lanNodeInfo?.let {
                    val unpairedNearby = state.lanPeers.filter { !it.isPaired }
                    if (unpairedNearby.isNotEmpty()) {
                        HorizontalDivider()
                        Text(
                            "${strings.nearbyDevicesLabel} (${unpairedNearby.size})",
                            style = MaterialTheme.typography.titleSmall,
                        )
                        unpairedNearby.forEach { peer ->
                            OutlinedCard(modifier = Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.small) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(12.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Icon(Icons.Rounded.Devices, null)
                                    Spacer(Modifier.width(10.dp))
                                    Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                                        ) {
                                            Text(peer.deviceName, style = MaterialTheme.typography.titleSmall)
                                            if (peer.relayAvailable) {
                                                Surface(
                                                    color = MaterialTheme.colorScheme.primaryContainer,
                                                    shape = RoundedCornerShape(50),
                                                ) {
                                                    Row(
                                                        modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp),
                                                        verticalAlignment = Alignment.CenterVertically,
                                                        horizontalArrangement = Arrangement.spacedBy(3.dp),
                                                    ) {
                                                        Icon(
                                                            Icons.Rounded.Bolt,
                                                            contentDescription = null,
                                                            modifier = Modifier.size(12.dp),
                                                            tint = MaterialTheme.colorScheme.onPrimaryContainer,
                                                        )
                                                        Text(
                                                            text = strings.relayBadgeLabel,
                                                            style = MaterialTheme.typography.labelSmall,
                                                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                                                        )
                                                    }
                                                }
                                            }
                                        }
                                        Text(
                                            text = "${peer.host}:${peer.port}",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                    FilledTonalButton(
                                        onClick = {
                                            pairPinInput = ""
                                            pendingPairPeer = peer
                                        },
                                        enabled = !state.isSyncingLan,
                                    ) {
                                        Text(strings.pairAction)
                                    }
                                }
                            }
                        }
                    }

                    HorizontalDivider()
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(strings.manualConnect, style = MaterialTheme.typography.labelLarge)
                        FilledTonalButton(
                            onClick = {
                                val clip = readTextFromClipboard().orEmpty()
                                val parsed = LanPairingCodec.parse(clip)
                                if (parsed != null) {
                                    manualHost = parsed.host
                                    manualPort = parsed.port.toString()
                                    manualFp = parsed.fingerprint
                                    if (parsed.token.isNotBlank()) {
                                        manualPin = parsed.token
                                    }
                                    state.notifyMessage(strings.clipboardFilledToast)
                                } else {
                                    state.notifyMessage(strings.clipboardNoPairingUri)
                                }
                            },
                        ) {
                            Icon(Icons.Rounded.ContentPaste, null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(strings.pastePairingUri)
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = manualHost,
                            onValueChange = { manualHost = it },
                            label = { Text(strings.ipAddressLabel) },
                            placeholder = { Text("192.168.1.100") },
                            singleLine = true,
                            modifier = Modifier.weight(1f),
                        )
                        OutlinedTextField(
                            value = manualPort,
                            onValueChange = { manualPort = it },
                            label = { Text(strings.portLabel) },
                            singleLine = true,
                            modifier = Modifier.width(110.dp),
                        )
                    }
                    OutlinedTextField(
                        value = manualFp,
                        onValueChange = { manualFp = it },
                        label = { Text(strings.peerFingerprintLabel) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = manualPin,
                        onValueChange = { manualPin = it },
                        label = { Text(strings.enterPinLabel) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    FilledTonalButton(
                        onClick = {
                            val p = manualPort.toIntOrNull()
                            if (p != null && manualHost.isNotBlank() && manualFp.isNotBlank()) {
                                if (manualPin.isNotBlank()) {
                                    state.pairWithLanPeer(manualHost.trim(), p, manualFp.trim(), manualPin.trim())
                                } else {
                                    state.syncWithLanPeer(manualHost.trim(), p, manualFp.trim())
                                }
                            }
                        },
                        enabled = !state.isSyncingLan,
                    ) {
                        Text(if (manualPin.isNotBlank()) strings.pairAndSyncAction else strings.connectAndSync)
                    }
                }
            }
        }

        // Card 3: Markdown Backup Export
        ElevatedCard(modifier = Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.large) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Icon(Icons.Rounded.Archive, null, tint = MaterialTheme.colorScheme.primary)
                Text(strings.exportMarkdownBackup, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                FilledTonalButton(
                    onClick = {
                        exportMarkdownBackupWithPicker(
                            client = state.client,
                            initialPath = state.client.vaultPath,
                            strings = strings,
                            onResult = { msg -> state.notifyMessage(msg) },
                        )
                    },
                ) { Text(strings.exportLabel) }
            }
        }
    }

    if (showQrScannerDialog) {
        PlatformQrScannerDialog(
            onScanned = { payload ->
                showQrScannerDialog = false
                state.connectFromPairingPayload(payload)
            },
            onDismiss = { showQrScannerDialog = false },
        )
    }

    pendingPairPeer?.let { target ->
        AlertDialog(
            onDismissRequest = { pendingPairPeer = null },
            title = { Text(strings.enterPinDialogTitle) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        text = "${target.deviceName} (${target.host}:${target.port})",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    OutlinedTextField(
                        value = pairPinInput,
                        onValueChange = { pairPinInput = it },
                        label = { Text(strings.enterPinLabel) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val secret = pairPinInput.trim()
                        pendingPairPeer = null
                        if (secret.isNotEmpty()) {
                            state.pairWithLanPeer(target.host, target.port, target.certFingerprint, secret)
                        }
                    },
                    enabled = pairPinInput.trim().length >= 6 && !state.isSyncingLan,
                ) { Text(strings.pairAndSyncAction) }
            },
            dismissButton = {
                TextButton(onClick = { pendingPairPeer = null }) { Text(strings.cancel) }
            },
        )
    }
}

@Composable
private fun SyncPrefSwitchRow(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f).padding(end = 12.dp),
        )
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}
