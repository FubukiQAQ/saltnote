package com.saltnote.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoStories
import androidx.compose.material.icons.rounded.CloudSync
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.Style
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.saltnote.ui.platform.pickLocalFolderPath
import com.saltnote.ui.screens.FsrsReviewScreen
import com.saltnote.ui.screens.NotesWorkspaceScreen
import com.saltnote.ui.screens.SettingsScreen
import com.saltnote.ui.screens.SyncScreen
import com.saltnote.ui.state.NavTab
import com.saltnote.ui.state.SaltAppState
import com.saltnote.ui.theme.SaltNoteTheme

@Composable
fun SaltNoteApp(state: SaltAppState) {
    SaltNoteTheme(darkTheme = state.darkTheme) {
        val snackbarHostState = remember { SnackbarHostState() }

        LaunchedEffect(state.snackbarMessage) {
            val msg = state.snackbarMessage
            if (!msg.isNullOrBlank()) {
                snackbarHostState.showSnackbar(msg)
                state.clearToast()
            }
        }

        BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
            val isWideScreen = maxWidth >= 840.dp

            Scaffold(
                snackbarHost = { SnackbarHost(snackbarHostState) },
                bottomBar = {
                    if (!isWideScreen) {
                        NavigationBar {
                            NavTab.entries.forEach { tab ->
                                NavigationBarItem(
                                    selected = state.selectedTab == tab,
                                    onClick = { state.selectedTab = tab },
                                    icon = { NavTabIcon(tab, state.dueCards.size) },
                                    label = { Text(tab.label) },
                                )
                            }
                        }
                    }
                },
            ) { innerPadding ->
                Row(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding),
                ) {
                    if (isWideScreen) {
                        NavigationRail(
                            containerColor = MaterialTheme.colorScheme.surfaceContainer,
                            header = {
                                Column(
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    modifier = Modifier.padding(vertical = 12.dp),
                                ) {
                                    Surface(
                                        shape = MaterialTheme.shapes.medium,
                                        color = MaterialTheme.colorScheme.primaryContainer,
                                        modifier = Modifier.size(44.dp),
                                    ) {
                                        Box(contentAlignment = Alignment.Center) {
                                            Icon(
                                                Icons.Rounded.AutoStories,
                                                contentDescription = "SaltNote",
                                                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                                            )
                                        }
                                    }
                                    Spacer(Modifier.height(4.dp))
                                    Text("Salt", style = MaterialTheme.typography.labelSmall)
                                }
                            },
                        ) {
                            NavTab.entries.forEach { tab ->
                                NavigationRailItem(
                                    selected = state.selectedTab == tab,
                                    onClick = { state.selectedTab = tab },
                                    icon = { NavTabIcon(tab, state.dueCards.size) },
                                    label = { Text(tab.label) },
                                )
                            }
                        }
                        VerticalDivider()
                    }

                    Box(modifier = Modifier.weight(1f).fillMaxHeight()) {
                        when (state.selectedTab) {
                            NavTab.Notes -> NotesWorkspaceScreen(state = state, isWideScreen = isWideScreen)
                            NavTab.Review -> FsrsReviewScreen(state = state)
                            NavTab.Sync -> SyncScreen(state = state)
                            NavTab.Settings -> SettingsScreen(state = state)
                        }
                    }
                }
            }

            // First-launch zero-config offline onboarding dialog (MVP M4)
            if (state.showOnboarding) {
                ZeroConfigOnboardingDialog(
                    vaultPath = state.client.vaultPath,
                    onStartOffline = { state.showOnboarding = false },
                    onChangeVault = {
                        val picked = pickLocalFolderPath("选择 SaltNote Vault 文件夹", state.client.vaultPath)
                        if (picked != null) {
                            state.switchVaultDirectory(picked)
                        }
                        state.showOnboarding = false
                    },
                )
            }
        }
    }
}

@Composable
private fun NavTabIcon(tab: NavTab, dueCount: Int) {
    val icon: ImageVector = when (tab) {
        NavTab.Notes -> Icons.Rounded.Description
        NavTab.Review -> Icons.Rounded.Style
        NavTab.Sync -> Icons.Rounded.CloudSync
        NavTab.Settings -> Icons.Rounded.Settings
    }
    if (tab == NavTab.Review && dueCount > 0) {
        BadgedBox(badge = { Badge { Text(dueCount.toString()) } }) {
            Icon(icon, contentDescription = tab.label)
        }
    } else {
        Icon(icon, contentDescription = tab.label)
    }
}

@Composable
private fun ZeroConfigOnboardingDialog(
    vaultPath: String,
    onStartOffline: () -> Unit,
    onChangeVault: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onStartOffline,
        icon = { Icon(Icons.Rounded.Shield, contentDescription = null) },
        title = { Text("欢迎使用 SaltNote") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "已为你准备好本地笔记目录，无需注册或登录即可直接开始记录。",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    text = vaultPath,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            Button(onClick = onStartOffline) {
                Text("开始使用")
            }
        },
        dismissButton = {
            TextButton(onClick = onChangeVault) {
                Text("更改目录")
            }
        },
    )
}
