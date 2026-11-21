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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.DarkMode
import androidx.compose.material.icons.rounded.Error
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.NetworkCheck
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.material3.Surface
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.saltnote.bridge.ProviderConfigDto
import com.saltnote.bridge.RouterConfigDto
import com.saltnote.ui.platform.pickLocalFolderPath
import com.saltnote.ui.state.SaltAppState

@Composable
fun SettingsScreen(state: SaltAppState) {
    val currentRouter = state.routerConfig
    var defaultTextId by remember(currentRouter) { mutableStateOf(currentRouter?.defaultTextProvider ?: "ollama-local") }
    var defaultVisionId by remember(currentRouter) { mutableStateOf(currentRouter?.defaultVisionProvider ?: "ollama-local") }
    var editableProviders by remember(currentRouter) {
        mutableStateOf(currentRouter?.providers ?: emptyList())
    }
    val keyInputs = remember { mutableStateMapOf<String, String>() }

    fun buildSecretSnapshot(): Map<String, String> {
        val out = mutableMapOf<String, String>()
        editableProviders.forEach { p ->
            val draft = keyInputs[p.id]?.trim().orEmpty()
            if (p.apiKeyRef.isNotBlank() && draft.isNotEmpty()) {
                out[p.apiKeyRef] = draft
            }
        }
        return out
    }

    fun clearSavedKeyDrafts() {
        editableProviders.forEach { p ->
            if (!keyInputs[p.id].isNullOrBlank()) {
                keyInputs.remove(p.id)
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("设置", style = MaterialTheme.typography.headlineMedium)

        ElevatedCard(modifier = Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.large) {
            Column(modifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Rounded.FolderOpen, null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(10.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text("笔记目录", style = MaterialTheme.typography.titleMedium)
                        Text(
                            text = state.client.vaultPath,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    FilledTonalButton(
                        onClick = {
                            val chosen = pickLocalFolderPath("选择笔记目录", state.client.vaultPath)
                            if (chosen != null) {
                                state.switchVaultDirectory(chosen)
                            }
                        },
                    ) { Text("切换") }
                }

                HorizontalDivider()

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Rounded.DarkMode, null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(10.dp))
                    Text("深色模式", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                    Switch(checked = state.darkTheme, onCheckedChange = { state.darkTheme = it })
                }
            }
        }

        ElevatedCard(modifier = Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.large) {
            Column(
                modifier = Modifier.padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.Key, null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(8.dp))
                        Text("AI 模型配置", style = MaterialTheme.typography.titleMedium)
                    }
                    Button(
                        onClick = {
                            val secrets = buildSecretSnapshot()
                            state.saveRouterConfig(
                                RouterConfigDto(
                                    defaultTextProvider = defaultTextId,
                                    defaultVisionProvider = defaultVisionId,
                                    providers = editableProviders,
                                ),
                                unsavedSecrets = secrets,
                            )
                            clearSavedKeyDrafts()
                        },
                    ) { Text("保存全部配置") }
                }

                val activeTextName = editableProviders.firstOrNull { it.id == defaultTextId }?.let { "${it.name} (${it.model})" } ?: defaultTextId
                val activeVisionName = editableProviders.firstOrNull { it.id == defaultVisionId }?.let { "${it.name} (${it.model})" } ?: defaultVisionId
                Surface(
                    color = MaterialTheme.colorScheme.surfaceContainerLow,
                    shape = MaterialTheme.shapes.small,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text("默认文本：$activeTextName", style = MaterialTheme.typography.bodySmall)
                        Text("默认视觉：$activeVisionName", style = MaterialTheme.typography.bodySmall)
                    }
                }

                editableProviders.forEachIndexed { idx, provider ->
                    val savedSecret = remember(provider.apiKeyRef, state.routerSecretEpoch) {
                        provider.apiKeyRef.isNotBlank() &&
                            state.client.secretVault.hasSecret(provider.apiKeyRef)
                    }
                    ProviderCardEditor(
                        provider = provider,
                        isDefaultText = defaultTextId == provider.id,
                        isDefaultVision = defaultVisionId == provider.id,
                        hasSavedSecret = savedSecret,
                        secretDraft = keyInputs[provider.id].orEmpty(),
                        isTesting = state.testingProviderId == provider.id,
                        testResult = state.connectivityResults.value[provider.id],
                        onUpdate = { updated ->
                            editableProviders = editableProviders.toMutableList().also { it[idx] = updated }
                        },
                        onSetDefaultText = {
                            defaultTextId = provider.id
                            val secrets = buildSecretSnapshot()
                            state.saveRouterConfig(
                                RouterConfigDto(defaultTextId, defaultVisionId, editableProviders),
                                unsavedSecrets = secrets,
                            )
                            clearSavedKeyDrafts()
                        },
                        onSetDefaultVision = {
                            defaultVisionId = provider.id
                            val secrets = buildSecretSnapshot()
                            state.saveRouterConfig(
                                RouterConfigDto(defaultTextId, defaultVisionId, editableProviders),
                                unsavedSecrets = secrets,
                            )
                            clearSavedKeyDrafts()
                        },
                        onSecretDraftChange = { raw ->
                            keyInputs[provider.id] = raw.replace("\n", "").replace("\r", "")
                        },
                        onSaveSecret = {
                            val secret = keyInputs[provider.id].orEmpty()
                            state.saveRouterConfig(
                                RouterConfigDto(defaultTextId, defaultVisionId, editableProviders),
                                unsavedSecrets = mapOf(provider.apiKeyRef to secret),
                            )
                            keyInputs.remove(provider.id)
                        },
                        onTestConnectivity = {
                            val secrets = buildSecretSnapshot()
                            state.saveAndTestProvider(
                                cfg = RouterConfigDto(defaultTextId, defaultVisionId, editableProviders),
                                providerId = provider.id,
                                unsavedSecrets = secrets,
                            )
                            clearSavedKeyDrafts()
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun ProviderCardEditor(
    provider: ProviderConfigDto,
    isDefaultText: Boolean,
    isDefaultVision: Boolean,
    hasSavedSecret: Boolean,
    secretDraft: String,
    isTesting: Boolean,
    testResult: com.saltnote.bridge.ConnectivityTestResultDto?,
    onUpdate: (ProviderConfigDto) -> Unit,
    onSetDefaultText: () -> Unit,
    onSetDefaultVision: () -> Unit,
    onSecretDraftChange: (String) -> Unit,
    onSaveSecret: () -> Unit,
    onTestConnectivity: () -> Unit,
) {
    var showSecret by remember(provider.id) { mutableStateOf(false) }

    OutlinedCard(modifier = Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.medium) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(provider.name, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                FilterChip(
                    selected = isDefaultText,
                    onClick = onSetDefaultText,
                    label = { Text("默认文本") },
                )
                FilterChip(
                    selected = isDefaultVision,
                    onClick = onSetDefaultVision,
                    label = { Text("默认视觉") },
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedTextField(
                    value = provider.baseUrl,
                    onValueChange = { raw ->
                        val cleaned = raw.replace("\n", "").replace("\r", "").replace(" ", "")
                        onUpdate(provider.copy(baseUrl = cleaned))
                    },
                    label = { Text("API 地址") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(
                        imeAction = ImeAction.Next,
                        keyboardType = KeyboardType.Uri,
                        autoCorrectEnabled = false,
                    ),
                    modifier = Modifier.weight(1.4f),
                )
                OutlinedTextField(
                    value = provider.model,
                    onValueChange = { raw ->
                        val cleaned = raw.replace("\n", "").replace("\r", "")
                        onUpdate(provider.copy(model = cleaned))
                    },
                    label = { Text("模型名称") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(
                        imeAction = ImeAction.Next,
                        autoCorrectEnabled = false,
                    ),
                    modifier = Modifier.weight(1f),
                )
            }

            if (provider.apiKeyRef.isNotBlank()) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedTextField(
                        value = secretDraft,
                        onValueChange = onSecretDraftChange,
                        label = {
                            Text(if (hasSavedSecret) "API Key（已保存，留空不变）" else "API Key")
                        },
                        visualTransformation = if (showSecret) VisualTransformation.None else PasswordVisualTransformation(),
                        trailingIcon = {
                            IconButton(onClick = { showSecret = !showSecret }) {
                                Icon(
                                    imageVector = if (showSecret) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility,
                                    contentDescription = if (showSecret) "隐藏密钥" else "显示密钥",
                                )
                            }
                        },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(
                            imeAction = ImeAction.Done,
                            keyboardType = KeyboardType.Password,
                            autoCorrectEnabled = false,
                        ),
                        modifier = Modifier.weight(1f),
                    )
                    FilledTonalButton(onClick = onSaveSecret) {
                        Text("保存密钥")
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                FilledTonalButton(
                    onClick = onTestConnectivity,
                    enabled = !isTesting,
                ) {
                    if (isTesting) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp,
                        )
                    } else {
                        Icon(Icons.Rounded.NetworkCheck, null, modifier = Modifier.size(16.dp))
                    }
                    Spacer(Modifier.width(6.dp))
                    Text(if (isTesting) "测试中…" else "测试连接")
                }
                if (testResult != null) {
                    Icon(
                        imageVector = if (testResult.ok) Icons.Rounded.CheckCircle else Icons.Rounded.Error,
                        contentDescription = null,
                        tint = if (testResult.ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(18.dp),
                    )
                    Text(
                        text = "${testResult.message} (${testResult.latencyMs}ms)",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (testResult.ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                    )
                }
            }
        }
    }
}
