package com.saltnote.ui.screens

import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.NetworkCheck
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
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
import androidx.compose.runtime.mutableStateMapOf
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
import com.saltnote.ui.components.SaltFilterChip
import com.saltnote.ui.i18n.AiOutputLanguage
import com.saltnote.ui.i18n.AppLanguage
import com.saltnote.ui.i18n.LocalSaltStrings
import com.saltnote.ui.platform.pickVaultDirectory
import com.saltnote.ui.state.SaltAppState

@Composable
fun SettingsScreen(state: SaltAppState) {
    val strings = LocalSaltStrings.current
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
        Text(strings.settingsTitle, style = MaterialTheme.typography.headlineMedium)

        ElevatedCard(modifier = Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.large) {
            Column(modifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Rounded.FolderOpen, null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(10.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(strings.vaultDirectoryTitle, style = MaterialTheme.typography.titleMedium)
                        Text(
                            text = state.client.vaultPath,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    FilledTonalButton(
                        onClick = {
                            pickVaultDirectory(
                                initialPath = state.client.vaultPath,
                                strings = strings,
                                onPicked = { chosen -> state.switchVaultDirectory(chosen) },
                                onMessage = { msg -> state.notifyMessage(msg) },
                            )
                        },
                    ) { Text(strings.switchAction) }
                }

                HorizontalDivider()

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Rounded.DarkMode, null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(10.dp))
                    Text(strings.darkThemeTitle, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                    Switch(checked = state.darkTheme, onCheckedChange = { state.darkTheme = it })
                }
            }
        }

        // Language Settings Card
        ElevatedCard(modifier = Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.large) {
            Column(modifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.Language, null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(10.dp))
                    Text(strings.languageCardTitle, style = MaterialTheme.typography.titleMedium)
                }

                Text(
                    text = strings.uiLanguageLabel,
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    AppLanguage.entries.forEach { lang ->
                        val label = if (lang == AppLanguage.System) strings.langFollowSystem else lang.nativeLabel
                        SaltFilterChip(
                            selected = state.uiLanguage.equals(lang.code, ignoreCase = true),
                            onClick = {
                                state.updateLanguageSettings(lang.code, state.aiOutputLanguage)
                            },
                            label = { Text(label) },
                        )
                    }
                }

                Text(
                    text = strings.aiOutputLanguageLabel,
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    AiOutputLanguage.entries.forEach { lang ->
                        val label = when (lang) {
                            AiOutputLanguage.FollowUi -> strings.langFollowUi
                            AiOutputLanguage.ZhCn -> AppLanguage.ZhCn.nativeLabel
                            AiOutputLanguage.ZhTw -> AppLanguage.ZhTw.nativeLabel
                            AiOutputLanguage.En -> AppLanguage.En.nativeLabel
                            AiOutputLanguage.Ja -> AppLanguage.Ja.nativeLabel
                            AiOutputLanguage.Ko -> AppLanguage.Ko.nativeLabel
                        }
                        SaltFilterChip(
                            selected = state.aiOutputLanguage.equals(lang.code, ignoreCase = true),
                            onClick = {
                                state.updateLanguageSettings(state.uiLanguage, lang.code)
                            },
                            label = { Text(label) },
                        )
                    }
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
                        Text(strings.aiConfigTitle, style = MaterialTheme.typography.titleMedium)
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
                    ) { Text(strings.saveAllConfig) }
                }

                val activeTextName = editableProviders.firstOrNull { it.id == defaultTextId }?.let { "${it.name} · ${it.model}" } ?: defaultTextId
                val activeVisionName = editableProviders.firstOrNull { it.id == defaultVisionId }?.let { "${it.name} · ${it.model}" } ?: defaultVisionId
                Surface(
                    color = MaterialTheme.colorScheme.surfaceContainerLow,
                    shape = MaterialTheme.shapes.small,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text("${strings.defaultTextLabel}: $activeTextName", style = MaterialTheme.typography.bodySmall)
                        Text("${strings.defaultVisionLabel}: $activeVisionName", style = MaterialTheme.typography.bodySmall)
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
                                RouterConfigDto(
                                    defaultTextProvider = defaultTextId,
                                    defaultVisionProvider = defaultVisionId,
                                    providers = editableProviders,
                                ),
                                unsavedSecrets = secrets,
                            )
                            clearSavedKeyDrafts()
                        },
                        onSetDefaultVision = {
                            defaultVisionId = provider.id
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
                        onSecretDraftChange = { raw ->
                            keyInputs[provider.id] = raw.replace("\n", "").replace("\r", "")
                        },
                        onSaveSecret = {
                            val secret = keyInputs[provider.id].orEmpty()
                            state.saveRouterConfig(
                                RouterConfigDto(
                                    defaultTextProvider = defaultTextId,
                                    defaultVisionProvider = defaultVisionId,
                                    providers = editableProviders,
                                ),
                                unsavedSecrets = mapOf(provider.apiKeyRef to secret),
                            )
                            keyInputs.remove(provider.id)
                        },
                        onTestConnectivity = {
                            val secrets = buildSecretSnapshot()
                            state.saveAndTestProvider(
                                cfg = RouterConfigDto(
                                    defaultTextProvider = defaultTextId,
                                    defaultVisionProvider = defaultVisionId,
                                    providers = editableProviders,
                                ),
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
    val strings = LocalSaltStrings.current
    var showSecret by remember(provider.id) { mutableStateOf(false) }

    OutlinedCard(modifier = Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.medium) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(provider.name, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                SaltFilterChip(
                    selected = isDefaultText,
                    onClick = onSetDefaultText,
                    label = { Text(strings.defaultTextChip) },
                )
                SaltFilterChip(
                    selected = isDefaultVision,
                    onClick = onSetDefaultVision,
                    label = { Text(strings.defaultVisionChip) },
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
                    label = { Text(strings.apiUrlLabel) },
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
                    label = { Text(strings.modelNameLabel) },
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
                        label = { Text(strings.apiKeyLabel) },
                        placeholder = if (hasSavedSecret) {
                            { Text(strings.savedPlaceholder) }
                        } else {
                            null
                        },
                        visualTransformation = if (showSecret) VisualTransformation.None else PasswordVisualTransformation(),
                        trailingIcon = {
                            IconButton(onClick = { showSecret = !showSecret }) {
                                Icon(
                                    imageVector = if (showSecret) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility,
                                    contentDescription = if (showSecret) strings.hideSecretDesc else strings.showSecretDesc,
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
                        Text(strings.saveKeyAction)
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
                    Text(if (isTesting) strings.testingAction else strings.testConnectionAction)
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
