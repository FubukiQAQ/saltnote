package com.saltnote.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Archive
import androidx.compose.material.icons.rounded.CloudSync
import androidx.compose.material.icons.rounded.Devices
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Wifi
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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.saltnote.bridge.WebDavConfigDto
import com.saltnote.ui.platform.pickLocalFolderPath
import com.saltnote.ui.state.SaltAppState

@Composable
fun SyncScreen(state: SaltAppState) {
    var endpoint by remember(state.webDavConfig) { mutableStateOf(state.webDavConfig.endpoint) }
    var rootPath by remember(state.webDavConfig) { mutableStateOf(state.webDavConfig.rootPath) }
    var username by remember(state.webDavConfig) { mutableStateOf(state.webDavConfig.username) }
    var passwordInput by remember { mutableStateOf("") }
    var webDavEnabled by remember(state.webDavConfig) { mutableStateOf(state.webDavConfig.enabled) }

    var manualHost by remember { mutableStateOf("") }
    var manualPort by remember { mutableStateOf("47821") }
    var manualFp by remember { mutableStateOf("") }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("多端同步", style = MaterialTheme.typography.headlineMedium)

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
                    Text("WebDAV 同步", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                    Switch(checked = webDavEnabled, onCheckedChange = { webDavEnabled = it })
                }

                OutlinedTextField(
                    value = endpoint,
                    onValueChange = { endpoint = it },
                    label = { Text("服务器地址") },
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
                        label = { Text("远端目录") },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                    OutlinedTextField(
                        value = username,
                        onValueChange = { username = it },
                        label = { Text("用户名") },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                }
                OutlinedTextField(
                    value = passwordInput,
                    onValueChange = { passwordInput = it },
                    label = {
                        val hasSaved = state.client.secretVault.hasSecret(state.webDavConfig.passwordKeyRef)
                        Text(if (hasSaved) "密码（已保存，留空不变）" else "密码")
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
                    ) { Text("保存配置") }

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
                        Text("立即同步")
                    }
                }

                state.lastSyncReport?.let { r ->
                    Surface(
                        color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.5f),
                        shape = MaterialTheme.shapes.small,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            text = "上次同步：上传 ${r.uploadedDeltas} · 拉取 ${r.downloadedDeltas}",
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(10.dp),
                        )
                    }
                }
            }
        }

        // Card 2: LAN Direct Sync
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
                    Text("局域网同步", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                    Switch(
                        checked = state.lanNodeInfo != null,
                        onCheckedChange = { state.toggleLanP2p(it) },
                    )
                }

                state.lanNodeInfo?.let { node ->
                    OutlinedCard(modifier = Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.small) {
                        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(
                                text = "本机地址：${node.hostIp.ifBlank { "获取中" }}:${node.port}",
                                style = MaterialTheme.typography.labelLarge,
                            )
                            Text(
                                text = "配对指纹：${node.fingerprint}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text("附近设备 (${state.lanPeers.size})", style = MaterialTheme.typography.titleSmall)
                        IconButton(onClick = { state.refreshLanPeers() }) {
                            Icon(Icons.Rounded.Refresh, "刷新")
                        }
                    }

                    state.lanPeers.forEach { peer ->
                        OutlinedCard(modifier = Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.small) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Icon(Icons.Rounded.Devices, null)
                                Spacer(Modifier.width(10.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(peer.deviceName, style = MaterialTheme.typography.titleSmall)
                                    Text(
                                        text = "${peer.host}:${peer.port}",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                Button(
                                    onClick = {
                                        state.syncWithLanPeer(peer.host, peer.port, peer.certFingerprint)
                                    },
                                    enabled = !state.isSyncingLan,
                                ) {
                                    if (state.isSyncingLan) {
                                        CircularProgressIndicator(
                                            modifier = Modifier.size(14.dp),
                                            strokeWidth = 2.dp,
                                            color = MaterialTheme.colorScheme.onPrimary,
                                        )
                                        Spacer(Modifier.width(6.dp))
                                    }
                                    Text("同步")
                                }
                            }
                        }
                    }

                    HorizontalDivider()
                    Text("手动连接", style = MaterialTheme.typography.labelLarge)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = manualHost,
                            onValueChange = { manualHost = it },
                            label = { Text("IP 地址") },
                            placeholder = { Text("192.168.1.100") },
                            singleLine = true,
                            modifier = Modifier.weight(1f),
                        )
                        OutlinedTextField(
                            value = manualPort,
                            onValueChange = { manualPort = it },
                            label = { Text("端口") },
                            singleLine = true,
                            modifier = Modifier.width(110.dp),
                        )
                    }
                    OutlinedTextField(
                        value = manualFp,
                        onValueChange = { manualFp = it },
                        label = { Text("对端配对指纹") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    FilledTonalButton(
                        onClick = {
                            val p = manualPort.toIntOrNull()
                            if (p != null && manualHost.isNotBlank() && manualFp.isNotBlank()) {
                                state.syncWithLanPeer(manualHost.trim(), p, manualFp.trim())
                            }
                        },
                        enabled = !state.isSyncingLan,
                    ) { Text("连接并同步") }
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
                Text("导出 Markdown 备份", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                FilledTonalButton(
                    onClick = {
                        val target = pickLocalFolderPath("选择导出目录", state.client.vaultPath)
                        if (target != null) {
                            state.exportMarkdownBackup(target)
                        }
                    },
                ) { Text("导出") }
            }
        }
    }
}
